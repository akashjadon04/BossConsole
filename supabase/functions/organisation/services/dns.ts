/**
 * Domain-ownership verification over DNS TXT.
 *
 * The claim being proven: whoever controls `_boss-verify.<domain>` controls the
 * domain, and therefore may bind it to an organisation. This matters more than
 * it looks -- a verified domain lets an org absorb every future signup with a
 * matching email address, so an unverified claim on `gmail.com` would be a
 * mass account capture.
 *
 * CONTRACT: never throws, and never echoes what it saw. A resolver error, a
 * timeout, NXDOMAIN and "the record is there but wrong" all produce
 * `verified: false` with no detail. Returning the observed records would turn
 * this into an open DNS resolver for the caller.
 */

const LOOKUP_TIMEOUT_MS = 5_000
const RECORD_PREFIX = "boss-org-verification="

export interface DnsVerification {
  verified: boolean
}

/**
 * A hostname safe to put into a resolver query.
 *
 * Anything that is not a plain dotted label sequence is refused rather than
 * escaped: the input reaches a resolver and, in the DoH fallback, a URL.
 * Trailing dots, wildcards, spaces, and anything non-ASCII are out. Punycode
 * (`xn--`) is already plain ASCII and passes.
 */
export function isValidHostname(host: string): boolean {
  if (typeof host !== "string") return false
  const value = host.trim().toLowerCase()
  if (value.length === 0 || value.length > 253) return false
  if (value.startsWith(".") || value.endsWith(".")) return false
  if (!value.includes(".")) return false
  return value.split(".").every((label) =>
    label.length >= 1 &&
    label.length <= 63 &&
    /^[a-z0-9]([a-z0-9-]*[a-z0-9])?$/.test(label)
  )
}

/**
 * Does `_boss-verify.<domain>` carry the expected token?
 *
 * Tries the platform resolver first and falls back to DNS-over-HTTPS, because
 * `Deno.resolveDns` requires the `--allow-net` permission for the resolver and
 * is not available in every edge runtime configuration. Both paths are bounded
 * by the same timeout; neither is allowed to reject.
 */
export async function verifyDomainToken(
  domain: string,
  expectedToken: string,
): Promise<DnsVerification> {
  if (!isValidHostname(domain) || !expectedToken) return { verified: false }

  const name = `_boss-verify.${domain.trim().toLowerCase()}`
  const expected = `${RECORD_PREFIX}${expectedToken}`

  const records = await resolveTxt(name)
  // Exact match on the whole record, not `includes`. A substring test would
  // accept `boss-org-verification=<someone else's token> extra`, and TXT
  // records are attacker-writable by definition on a domain we do not yet
  // trust.
  return { verified: records.some((record) => record === expected) }
}

/** TXT records for `name`, or [] for every failure mode. */
async function resolveTxt(name: string): Promise<string[]> {
  const native = await resolveTxtNative(name)
  if (native !== null) return native
  return await resolveTxtDoh(name) ?? []
}

/**
 * Platform resolver. Returns null when unavailable (so the caller falls back),
 * [] when it answered but found nothing.
 *
 * The distinction matters: treating "no resolver" as "no records" would make
 * verification permanently impossible on a runtime without the permission,
 * with no signal other than admins reporting that Verify never works.
 */
async function resolveTxtNative(name: string): Promise<string[] | null> {
  const resolve = (Deno as { resolveDns?: unknown }).resolveDns
  if (typeof resolve !== "function") return null

  try {
    const chunks = await withTimeout(
      (resolve as (n: string, t: string) => Promise<string[][]>)(name, "TXT"),
      LOOKUP_TIMEOUT_MS,
    )
    if (chunks === null) return [] // timed out: answered nothing, do not retry over DoH
    // A TXT record longer than 255 bytes arrives as multiple strings that must
    // be concatenated with no separator.
    return chunks.map((parts) => parts.join(""))
  } catch (error) {
    // NotFound is a real answer: the record does not exist.
    if (error instanceof Deno.errors.NotFound) return []
    // PermissionDenied means no resolver for us - fall back.
    if (error instanceof Deno.errors.PermissionDenied) return null
    return []
  }
}

/** DNS-over-HTTPS fallback. Returns null if the fallback itself failed. */
async function resolveTxtDoh(name: string): Promise<string[] | null> {
  try {
    const url = `https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(name)}&type=TXT`
    const response = await withTimeout(
      fetch(url, { headers: { accept: "application/dns-json" } }),
      LOOKUP_TIMEOUT_MS,
    )
    if (response === null || !response.ok) return null

    const body = await response.json() as { Answer?: Array<{ data?: unknown }> }
    if (!Array.isArray(body.Answer)) return []

    return body.Answer
      .map((answer) => (typeof answer.data === "string" ? answer.data : ""))
      // The JSON API returns TXT data quoted, and splits long records into
      // several adjacent quoted strings.
      .map((data) => data.replace(/"\s*"/g, "").replace(/^"|"$/g, ""))
      .filter((data) => data.length > 0)
  } catch {
    return null
  }
}

/** Resolves to null on timeout rather than rejecting. */
async function withTimeout<T>(promise: Promise<T>, ms: number): Promise<T | null> {
  // ReturnType, not `number`: setTimeout is typed as returning a Timeout
  // object under the Node-compatible lib and a number under the Deno one,
  // and which of those wins depends on how the file is entered.
  let timer: ReturnType<typeof setTimeout> | undefined
  try {
    return await Promise.race([
      promise,
      new Promise<null>((resolve) => {
        timer = setTimeout(() => resolve(null), ms)
      }),
    ])
  } catch {
    return null
  } finally {
    if (timer !== undefined) clearTimeout(timer)
  }
}
