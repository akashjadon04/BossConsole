/**
 * The `?t=` handoff exchange, shared by every page the desktop app can open directly.
 *
 * EXTRACTED BECAUSE IT WAS ONLY ON ONE ROUTE. The desktop panel has two buttons: Open page,
 * which opens `/o/<slug>?t=<token>`, and Configure, which opens `/o/<slug>/admin?t=<token>`.
 * Only `/o/:slug` ever looked at `t`. The admin route went straight to requireOrgSession, found
 * no cookie, and answered "Session expired" - so Configure never worked from a cold start, while
 * Open page followed by the in-page Configure link did, because the first navigation had left a
 * cookie behind. The fix has to live in one place or the next page to be linked directly will
 * reintroduce it.
 *
 * THE ORDER IS THE SECURITY PROPERTY, and it is unchanged from the original:
 *
 *   1. refuse a cross-site navigation (login CSRF)
 *   2. rate limit the exchange (a brake on token guessing)
 *   3. consume the token, service-role, single use
 *   4. cross-check the token's org against the slug in the path
 *   5. Set-Cookie
 *   6. 302 to the same URL WITHOUT `t`
 *
 * Step 6 is a 302 rather than a 303 on purpose. Both work for a GET, but 302 replaces the current
 * navigation, so the `?t=` URL leaves the address bar AND the history entry. The token must not
 * survive a back button.
 */

import type { Context } from "hono"
import { exchangeHandoffToken, urlWithoutToken } from "../services/handoff.ts"
import { htmlResponse, redirectResponse } from "../utils/responses.ts"
import { mintSession, newCsrfToken, sessionCookieHeader } from "../utils/session.ts"
import type { RequestFacts } from "../utils/request.ts"
import { clientKey, rateLimit } from "../utils/rate-limit.ts"
import { errorPage, SESSION_EXPIRED_MESSAGE } from "../views/error.ts"

/** 20 exchanges per minute per client. Generous for a human, useless for a loop. */
const HANDOFF_LIMIT = 20
const HANDOFF_WINDOW_SECONDS = 60

/**
 * Consume a `?t=` token if there is one, and return the response that should be sent.
 *
 * Returns `null` when there is NO token, which means the caller carries on to its normal
 * session-and-authority path. Anything non-null is final: either an error page or the 302 that
 * drops the token and carries the cookie.
 *
 * [route] is the path to come back to, WITHOUT the base path and without the token - so
 * `/o/acme` or `/o/acme/admin`. It is built by the caller from its own already-validated slug,
 * never from the request URL: taking a redirect target from the query string is how this would
 * become an open redirect, and taking it from the raw path would let a crafted URL echo itself
 * into a Location header.
 */
export async function consumeHandoffToken(
  ctx: Context,
  facts: RequestFacts,
  slug: string,
  route: string,
): Promise<Response | null> {
  const search = new URL(ctx.req.url).searchParams
  const token = search.get("t")
  if (!token) return null

  // A state-changing GET, and the one request that does not follow the session -> CSRF -> probe
  // order the rest of this function keeps to. Consuming a token and issuing Set-Cookie means any
  // page could navigate a victim to /o/<slug>?t=<attacker token> and replace their org session
  // with the attacker's identity - login CSRF. The attacker gains nothing, but the victim can be
  // induced to type into a settings form belonging to somebody else's organisation.
  //
  // Sec-Fetch-Site is not settable by page script. The desktop app opens this as a top-level
  // navigation from a boss:// link, which yields `none`, so refusing only `cross-site` costs the
  // real flow nothing.
  if (ctx.req.header("sec-fetch-site") === "cross-site") {
    return htmlResponse(
      (nonce) =>
        errorPage({
          nonce,
          title: "Session unavailable - BOSS",
          heading: "Open this from BOSS",
          message: SESSION_EXPIRED_MESSAGE,
        }),
      { status: 400 },
    )
  }

  const limit = rateLimit(
    `handoff:${clientKey(ctx.req.raw.headers)}`,
    HANDOFF_LIMIT,
    HANDOFF_WINDOW_SECONDS,
  )
  if (!limit.allowed) {
    return htmlResponse(
      (nonce) =>
        errorPage({
          nonce,
          title: "Too many attempts - BOSS",
          heading: "Too many attempts",
          message: "Please wait a moment and open the page again from BOSS.",
        }),
      { status: 429, headers: { "Retry-After": String(limit.retryAfterSeconds) } },
    )
  }

  const exchange = await exchangeHandoffToken(token, slug)
  if (!exchange.ok) {
    // Both failure reasons render the same page. A distinguishable slug_mismatch would confirm
    // that a guessed token is real and tell the holder which org it belongs to.
    return htmlResponse(
      (nonce) =>
        errorPage({
          nonce,
          title: "Session unavailable - BOSS",
          heading: "This link has expired",
          message: SESSION_EXPIRED_MESSAGE,
        }),
      { status: 400 },
    )
  }

  const value = await mintSession({
    sub: exchange.identity.userId,
    org: exchange.identity.orgId,
    slug: exchange.identity.orgSlug,
    csrf: newCsrfToken(),
    pur: exchange.identity.purpose,
  })

  // Rebuilt from the configured base path, so the token cannot survive in the Location by way of
  // an echoed request URL.
  const location = urlWithoutToken(facts.basePath, route, search)

  return redirectResponse(location, {
    status: 302,
    headers: { "Set-Cookie": sessionCookieHeader(value, facts.secure, facts.basePath) },
  })
}
