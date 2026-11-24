/**
 * The web session cookie.
 *
 * WHY A STATELESS SIGNED COOKIE AND NOT A DB ROW. The cookie is an
 * AUTHENTICATION token, not an authorization one. It says "this browser is
 * user X, who arrived via a handoff for org Y" and nothing else. Authority is
 * re-derived from user_is_org_admin / user_is_org_member on EVERY request, so
 * the cookie carries no privilege claim and server-side revocation buys nothing
 * the membership probe does not already give: remove someone from the org and
 * their live web session dies on their next click, for free.
 *
 * WHY NOT A JWT. The format is deliberately `b64url(payload).b64url(sig)` with
 * NO algorithm field. A JWT header invites algorithm negotiation, and the
 * `alg: none` and RS256-verified-as-HS256 confusions are entirely a product of
 * letting the token describe how to check itself. Here there is exactly one
 * algorithm, chosen by the verifier, and no dependency to keep patched.
 *
 * WHAT THE PAYLOAD MAY NOT CONTAIN: anything about admin-ness. A test asserts
 * that an injected {"admin":true} is ignored. The only fields read back are the
 * ones in SessionPayload.
 */

import { requireSessionSecrets } from "./config.ts"

/** 30 minutes, absolute. No sliding renewal - see mintSession. */
export const SESSION_TTL_SECONDS = 30 * 60

const COOKIE_NAME_SECURE = "__Secure-boss_org"
const COOKIE_NAME_INSECURE = "boss_org"

export interface SessionPayload {
  /** auth.users.id of the authenticated human. */
  sub: string
  /** organisations.id this session was handed off for. */
  org: string
  /** organisations.slug, so a route can cross-check the URL without a lookup. */
  slug: string
  /** CSRF nonce. Lives inside the signed HttpOnly cookie - see csrf.ts. */
  csrf: string
  /** Handoff purpose, e.g. `org_view` / `org_admin`. Informational only. */
  pur: string
  /** Issued at, epoch seconds. */
  iat: number
  /** Expiry, epoch seconds. */
  exp: number
}

/**
 * Cookie name for this request.
 *
 * `__Secure-` rather than `__Host-`: the latter mandates `Path=/`, which would
 * attach our cookie to every other function on api.risaboss.com. The shadowing
 * protection `__Host-` would have bought comes instead from the HMAC plus the
 * "try every candidate" rule in readSession.
 *
 * Over plain http the prefix is dropped entirely, because a `__Secure-` cookie
 * without the Secure attribute is rejected by the browser and a Secure cookie
 * over http is silently discarded. On a local stack that would loop the handoff
 * exchange forever with no visible error.
 */
export function cookieName(secure: boolean): string {
  return secure ? COOKIE_NAME_SECURE : COOKIE_NAME_INSECURE
}

function b64urlEncode(bytes: Uint8Array): string {
  let binary = ""
  for (const b of bytes) binary += String.fromCharCode(b)
  return btoa(binary).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "")
}

function b64urlDecode(value: string): Uint8Array | null {
  // Reject anything outside the alphabet rather than letting atob coerce it.
  if (!/^[A-Za-z0-9_-]*$/.test(value)) return null
  const padded = value.replace(/-/g, "+").replace(/_/g, "/") +
    "=".repeat((4 - (value.length % 4)) % 4)
  try {
    const binary = atob(padded)
    const out = new Uint8Array(binary.length)
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i)
    return out
  } catch {
    return null
  }
}

const keyCache = new Map<string, Promise<CryptoKey>>()

function hmacKey(secret: string): Promise<CryptoKey> {
  let cached = keyCache.get(secret)
  if (!cached) {
    cached = crypto.subtle.importKey(
      "raw",
      new TextEncoder().encode(secret),
      { name: "HMAC", hash: "SHA-256" },
      false,
      ["sign"],
    )
    keyCache.set(secret, cached)
  }
  return cached
}

async function sign(secret: string, message: string): Promise<Uint8Array> {
  const key = await hmacKey(secret)
  const sig = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(message))
  return new Uint8Array(sig)
}

/**
 * Constant-time byte comparison.
 *
 * Verification is only ever done against a freshly computed HMAC, so a variable
 * time compare would leak the expected signature one byte at a time to a caller
 * who can measure it.
 */
function timingSafeEqual(a: Uint8Array, b: Uint8Array): boolean {
  if (a.length !== b.length) return false
  let diff = 0
  for (let i = 0; i < a.length; i++) diff |= a[i] ^ b[i]
  return diff === 0
}

/** A fresh CSRF nonce, 128 bits. */
export function newCsrfToken(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return b64urlEncode(bytes)
}

/**
 * Mint a signed session value.
 *
 * The TTL is absolute and there is no renewal path: reopening the page from the
 * desktop plugin mints a fresh handoff token, which costs one RPC, and in
 * exchange a web session can never outlive 30 minutes from the handoff that
 * created it.
 */
export async function mintSession(
  input: Omit<SessionPayload, "iat" | "exp">,
  nowSeconds: number = Math.floor(Date.now() / 1000),
): Promise<string> {
  const { signing } = requireSessionSecrets()
  const payload: SessionPayload = {
    ...input,
    iat: nowSeconds,
    exp: nowSeconds + SESSION_TTL_SECONDS,
  }
  const encoded = b64urlEncode(new TextEncoder().encode(JSON.stringify(payload)))
  const sig = await sign(signing, encoded)
  return `${encoded}.${b64urlEncode(sig)}`
}

/**
 * Verify one cookie value. Returns null for anything that is not a live,
 * correctly signed session - malformed, wrong signature, expired, or missing a
 * required field.
 */
export async function verifySession(
  value: string,
  nowSeconds: number = Math.floor(Date.now() / 1000),
): Promise<SessionPayload | null> {
  const { verifying } = requireSessionSecrets()

  const dot = value.indexOf(".")
  if (dot <= 0 || dot !== value.lastIndexOf(".")) return null
  const encoded = value.slice(0, dot)
  const providedSig = b64urlDecode(value.slice(dot + 1))
  if (!providedSig || providedSig.length !== 32) return null

  let verified = false
  for (const secret of verifying) {
    const expected = await sign(secret, encoded)
    // No early break on success: keep the loop's cost independent of WHICH key
    // matched, so rotation state is not observable through timing.
    if (timingSafeEqual(expected, providedSig)) verified = true
  }
  if (!verified) return null

  const raw = b64urlDecode(encoded)
  if (!raw) return null

  let parsed: unknown
  try {
    parsed = JSON.parse(new TextDecoder().decode(raw))
  } catch {
    return null
  }
  if (typeof parsed !== "object" || parsed === null) return null

  const p = parsed as Record<string, unknown>
  const str = (k: string) => (typeof p[k] === "string" && p[k] !== "" ? p[k] as string : null)
  const sub = str("sub"), org = str("org"), slug = str("slug"), csrf = str("csrf")
  const pur = str("pur") ?? "org_view"
  const iat = typeof p.iat === "number" ? p.iat : null
  const exp = typeof p.exp === "number" ? p.exp : null
  if (!sub || !org || !slug || !csrf || iat === null || exp === null) return null

  if (exp <= nowSeconds) return null

  // A signed cookie cannot outlive the TTL even if the signer was buggy or the
  // key leaked to something that mints its own exp.
  if (exp - iat > SESSION_TTL_SECONDS) return null

  // Only these fields are returned. Anything else in the payload -- notably an
  // injected "admin": true -- is dropped here and can never reach a handler.
  return { sub, org, slug, csrf, pur, iat, exp }
}

/**
 * Every value sent under `name`, in header order.
 *
 * A browser sends duplicates when cookies differ by Domain or Path, and an
 * attacker who controls any subdomain can plant one. Taking only the first
 * would let a planted cookie shadow the real session -- a self-inflicted DoS at
 * best. The caller tries all of them and accepts the first that verifies.
 */
export function cookieValues(cookieHeader: string | null, name: string): string[] {
  if (!cookieHeader) return []
  const out: string[] = []
  for (const part of cookieHeader.split(";")) {
    const eq = part.indexOf("=")
    if (eq < 0) continue
    if (part.slice(0, eq).trim() !== name) continue
    out.push(part.slice(eq + 1).trim())
  }
  return out
}

/**
 * The live session for this request, or null.
 *
 * Tries both cookie names so a deployment that flips between http and https
 * (local -> hosted, or a proxy change) does not strand a browser holding the
 * other one.
 */
export async function readSession(
  cookieHeader: string | null,
  secure: boolean,
  nowSeconds: number = Math.floor(Date.now() / 1000),
): Promise<SessionPayload | null> {
  // Asserted up front, BEFORE the "are there any cookies" check, and this
  // ordering is the point. Verification alone would only consult the secret
  // when a cookie happens to be present, so a deployment with no
  // ORG_SESSION_SECRET would answer "your session expired" to every visitor
  // who arrives without one -- a configuration outage wearing the costume of a
  // routine expiry, which nobody would think to escalate. Throwing here makes
  // the first request of any kind surface it as a 503.
  requireSessionSecrets()

  const names = secure
    ? [COOKIE_NAME_SECURE, COOKIE_NAME_INSECURE]
    : [COOKIE_NAME_INSECURE, COOKIE_NAME_SECURE]

  for (const name of names) {
    for (const value of cookieValues(cookieHeader, name)) {
      const session = await verifySession(value, nowSeconds)
      if (session) return session
    }
  }
  return null
}

/** `Set-Cookie` establishing the session. */
export function sessionCookieHeader(value: string, secure: boolean, path: string): string {
  const attrs = [
    `${cookieName(secure)}=${value}`,
    `Path=${path}`,
    `Max-Age=${SESSION_TTL_SECONDS}`,
    "HttpOnly",
    // Lax, not Strict: the desktop app navigates the embedded browser here from
    // a boss:// deep link, and Strict would withhold the cookie on that
    // cross-site top-level navigation, showing a logged-out page on first load.
    // Lax alone is NOT the CSRF defence -- see csrf.ts.
    "SameSite=Lax",
    "Priority=High",
  ]
  if (secure) attrs.push("Secure")
  return attrs.join("; ")
}

/** `Set-Cookie` clearing the session, for sign-out and for a failed exchange. */
export function clearCookieHeader(secure: boolean, path: string): string {
  const attrs = [`${cookieName(secure)}=`, `Path=${path}`, "Max-Age=0", "HttpOnly", "SameSite=Lax"]
  if (secure) attrs.push("Secure")
  return attrs.join("; ")
}
