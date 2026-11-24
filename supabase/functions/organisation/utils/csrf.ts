/**
 * CSRF: cookie-bound double-submit.
 *
 * WHY SameSite=Lax IS NOT ENOUGH HERE. api.risaboss.com also serves
 * plugin-store's Swagger UI, which loads third-party CDN JavaScript, and
 * passkey's HTML pages. Those are the SAME ORIGIN as this function, so
 * SameSite gives nothing against them and `Path` scoping gives nothing either
 * (same-origin script can fetch any path with credentials). Lax only stops the
 * cross-SITE case.
 *
 * The nonce therefore lives INSIDE the signed HttpOnly session cookie rather
 * than in a readable cookie of its own. Same-origin script cannot read it, and
 * because it is bound into the session payload it cannot be swapped between
 * sessions: a token minted for session A fails against session B.
 *
 * Order in every mutating handler, and the order matters:
 *   requireCsrf  ->  live is_org_admin probe  ->  rate limit  ->  validate  ->  RPC
 * CSRF first, because a forged request should be rejected before it can consume
 * rate-limit budget or reach a probe that touches the database.
 */

import type { SessionPayload } from "./session.ts"

/** Hidden form field carrying the nonce back. */
export const CSRF_FIELD = "csrf_token"

export type CsrfFailure =
  | "missing_token"
  | "bad_token"
  | "bad_origin"

/** Constant-time string comparison over UTF-8 bytes. */
function timingSafeEqualStrings(a: string, b: string): boolean {
  const enc = new TextEncoder()
  const x = enc.encode(a)
  const y = enc.encode(b)
  if (x.length !== y.length) return false
  let diff = 0
  for (let i = 0; i < x.length; i++) diff |= x[i] ^ y[i]
  return diff === 0
}

/**
 * True when the request's declared initiator is this same site.
 *
 * Sec-Fetch-Site is the reliable signal where it exists; every browser that
 * ships SameSite also ships it. `Origin` is the fallback for the rest.
 *
 * A request with NEITHER header is refused. That is stricter than the common
 * "no Origin means same-origin" convention, and deliberately so: form posts
 * from a browser always carry at least one, so the header-less case is a
 * non-browser client, which has no business posting to an HTML admin form.
 */
export function originIsSameSite(
  secFetchSite: string | null,
  origin: string | null,
  expectedOrigin: string | null,
): boolean {
  if (secFetchSite) {
    return secFetchSite === "same-origin" || secFetchSite === "same-site" || secFetchSite === "none"
  }
  if (origin && expectedOrigin) {
    return origin.toLowerCase() === expectedOrigin.toLowerCase()
  }
  return false
}

/**
 * Validate a mutating request's CSRF posture.
 *
 * `submitted` is the form field; `session` supplies the expected nonce. Returns
 * null when the request is acceptable, or the reason it is not.
 *
 * Sec-Fetch-Site: "none" is accepted because it means a user-initiated
 * navigation with no initiator (typed URL, bookmark), which cannot be forged by
 * another page. It never accompanies a cross-origin form post.
 */
export function checkCsrf(input: {
  session: SessionPayload
  submitted: unknown
  secFetchSite: string | null
  origin: string | null
  expectedOrigin: string | null
}): CsrfFailure | null {
  if (
    !originIsSameSite(input.secFetchSite, input.origin, input.expectedOrigin)
  ) {
    return "bad_origin"
  }
  if (typeof input.submitted !== "string" || input.submitted.length === 0) {
    return "missing_token"
  }
  if (!timingSafeEqualStrings(input.submitted, input.session.csrf)) {
    return "bad_token"
  }
  return null
}
