/**
 * Per-request derivations every route needs: is this https, what is the
 * browser-facing origin, and who does the cookie say this is.
 *
 * Kept apart from session.ts so that module stays free of Hono and stays a pure
 * crypto/parsing unit the tests can drive directly.
 */

import type { Context } from "hono"
import { isSecureRequest, publicBasePath } from "./config.ts"
import { readSession, type SessionPayload } from "./session.ts"

export interface RequestFacts {
  /** Whether the BROWSER reached us over https, which decides the cookie name. */
  secure: boolean
  /** Browser-facing path prefix. Never derived from the request URL. */
  basePath: string
  /** Origin the browser should have sent, for the CSRF check. */
  expectedOrigin: string | null
  session: SessionPayload | null
}

export function requestIsSecure(ctx: Context): boolean {
  return isSecureRequest(ctx.req.url, ctx.req.header("x-forwarded-proto") ?? null)
}

/**
 * The origin a same-origin request should carry.
 *
 * Built from the Host header, which is client-supplied. That is fine for this
 * use: the check is "did the browser say it came from where it is", and a
 * browser sets Origin and Host consistently. An attacker who forges both has
 * gained nothing, because Sec-Fetch-Site is the primary signal and it is not
 * settable by page script.
 */
export function expectedOrigin(ctx: Context): string | null {
  const host = ctx.req.header("host")
  if (!host) return null
  const scheme = requestIsSecure(ctx) ? "https" : "http"
  return `${scheme}://${host}`
}

export async function readRequestFacts(ctx: Context): Promise<RequestFacts> {
  const secure = requestIsSecure(ctx)
  return {
    secure,
    basePath: publicBasePath(),
    expectedOrigin: expectedOrigin(ctx),
    session: await readSession(ctx.req.header("cookie") ?? null, secure),
  }
}

/**
 * A form field as a trimmed string, or null.
 *
 * `parseBody` yields `string | File`, and a File where a string was expected is
 * a caller doing something odd, not a value to coerce.
 */
export function field(body: Record<string, unknown>, name: string): string | null {
  const value = body[name]
  if (typeof value !== "string") return null
  const trimmed = value.trim()
  return trimmed.length > 0 ? trimmed : null
}

/**
 * A form field as a trimmed string, preserving the empty string.
 *
 * [field] returns null for both "absent" and "present but empty", which is right for the fields
 * where empty means "leave it alone" and wrong for every field a user can deliberately CLEAR.
 * Returns null only when the key is genuinely absent, so the caller can send an explicit empty
 * string and mean it.
 */
export function rawField(body: Record<string, unknown>, name: string): string | null {
  const value = body[name]
  return typeof value === "string" ? value.trim() : null
}

/** A checkbox: present means true. */
export function checkbox(body: Record<string, unknown>, name: string): boolean {
  return typeof body[name] === "string" && (body[name] as string).length > 0
}

/** A form field as a bounded integer, or null when absent or out of range. */
export function intField(
  body: Record<string, unknown>,
  name: string,
  min: number,
  max: number,
): number | null {
  const raw = field(body, name)
  if (raw === null) return null
  if (!/^\d+$/.test(raw)) return null
  const value = Number.parseInt(raw, 10)
  if (!Number.isFinite(value) || value < min || value > max) return null
  return value
}

/**
 * A UUID from a form field, or null.
 *
 * Validated here rather than left to Postgres: an invalid uuid reaches the RPC
 * as a cast error, which surfaces as a generic "something went wrong" instead
 * of the specific message the page could have shown.
 */
export function uuidField(body: Record<string, unknown>, name: string): string | null {
  const raw = field(body, name)
  if (raw === null) return null
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(raw) ? raw : null
}

/**
 * True when `slug` is a syntactically valid organisation slug.
 *
 * Mirrors the database CHECK exactly (`^[a-z][a-z0-9_]{1,30}$`). Underscores,
 * never hyphens: role names derive from the slug and are validated
 * `^[a-z][a-z0-9_]{2,50}$`, so a hyphen would make the mapping partial.
 */
export function isValidSlug(slug: string): boolean {
  return /^[a-z][a-z0-9_]{1,30}$/.test(slug)
}

/**
 * Is this a website we are willing to render as a link?
 *
 * http and https only, no whitespace, 500 characters at most - the same rule
 * `validate_website` applies in the database. Duplicated deliberately: the RPC
 * keeps its own check for callers that are not this route, and this copy exists so
 * the admin page can name the offending field instead of collapsing to its generic
 * refusal.
 */
export function isHttpUrl(value: string): boolean {
  return value.length <= 500 && /^https?:\/\/[^\s]+$/i.test(value)
}
