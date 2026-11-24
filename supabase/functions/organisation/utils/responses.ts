/**
 * Response construction and the security headers every response carries.
 *
 * These are applied here, at the single place responses are built, rather than
 * as middleware over `ctx.html(...)`, so that a handler cannot accidentally
 * return a bare Response and skip them.
 */

import { cspNonce } from "./html.ts"

/**
 * Headers on EVERY response, HTML or redirect.
 *
 * - `no-store`, and `Vary: Cookie` behind it, because these pages are
 *   per-user: a shared cache holding one member's page and serving it to
 *   another is the whole failure.
 * - `Referrer-Policy: no-referrer` is not decoration. The handoff URL carries
 *   `?t=<bearer token>`, and without this any resource the page references
 *   would ship that token in a Referer header. The 302 strip removes it from
 *   history; this removes it from the wire.
 * - `X-Frame-Options: DENY` because the admin page is all state-changing forms.
 */
function baseSecurityHeaders(): Record<string, string> {
  return {
    "Cache-Control": "no-store, max-age=0",
    "Vary": "Cookie",
    "X-Content-Type-Options": "nosniff",
    "Referrer-Policy": "no-referrer",
    "X-Frame-Options": "DENY",
  }
}

/**
 * Content-Security-Policy for an HTML response.
 *
 * `default-src 'none'` and then only what a page actually needs. There is no
 * `unsafe-inline`: the nonce covers our own inline style and script blocks, and
 * an injected one has no nonce. This is the second line of defence behind
 * esc() -- the first line is that org-controlled strings are escaped at every
 * interpolation.
 *
 * `form-action 'self'` matters specifically here: it stops an injection from
 * repointing an admin form at another origin and harvesting the CSRF nonce.
 */
function contentSecurityPolicy(nonce: string): string {
  return [
    "default-src 'none'",
    `script-src 'nonce-${nonce}'`,
    `style-src 'nonce-${nonce}'`,
    "img-src 'self' data:",
    "connect-src 'self'",
    "form-action 'self'",
    "base-uri 'none'",
    "frame-ancestors 'none'",
  ].join("; ")
}

export interface HtmlOptions {
  status?: number
  /** Extra headers, e.g. Set-Cookie. */
  headers?: Record<string, string>
}

/**
 * Render an HTML response.
 *
 * `build` receives the nonce so the page can stamp it on its own <style> and
 * <script> tags. Generating the nonce here rather than in the view guarantees
 * the header and the markup can never disagree.
 */
export function htmlResponse(
  build: (nonce: string) => string,
  options: HtmlOptions = {},
): Response {
  const nonce = cspNonce()
  const headers = new Headers({
    "Content-Type": "text/html; charset=utf-8",
    "Content-Security-Policy": contentSecurityPolicy(nonce),
    ...baseSecurityHeaders(),
    ...(options.headers ?? {}),
  })
  return new Response(build(nonce), { status: options.status ?? 200, headers })
}

/**
 * A redirect carrying the same security headers.
 *
 * `status` defaults to 303 (See Other), which is what a form POST must answer
 * with: it converts the follow-up to a GET, so a reload does not re-submit.
 * The handoff strip uses 302 instead - see routes/org-page.ts for why.
 */
export function redirectResponse(
  location: string,
  options: { status?: number; headers?: Record<string, string> } = {},
): Response {
  const headers = new Headers({
    Location: location,
    ...baseSecurityHeaders(),
    ...(options.headers ?? {}),
  })
  return new Response(null, { status: options.status ?? 303, headers })
}

/** JSON, for /health only. Pages are always HTML. */
export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: new Headers({
      "Content-Type": "application/json; charset=utf-8",
      ...baseSecurityHeaders(),
    }),
  })
}
