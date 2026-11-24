/**
 * GET /o/:slug -- the member overview, and the handoff exchange that gets you
 * there.
 *
 * THE `?t=` FLOW, in order, because the order is the security property:
 *
 *   1. rate limit the exchange (a brake on token guessing)
 *   2. consume the token, service-role, single use
 *   3. cross-check the token's org against the slug in the path
 *   4. Set-Cookie
 *   5. 302 to the same URL WITHOUT `t`
 *
 * Step 5 is a 302 and not a 303 on purpose. Both would work for a GET, but 302
 * is what a browser treats as "the resource is over here for now" and, more to
 * the point, it replaces the current navigation so the `?t=` URL leaves both the
 * address bar and the history entry. The token must not survive a back button.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { consumeHandoffToken } from "./handoff-exchange.ts"
import { isOrgMember } from "../services/authority.ts"
import { loadOrgPageData } from "../services/org.ts"
import { htmlResponse } from "../utils/responses.ts"
import { isValidSlug, readRequestFacts } from "../utils/request.ts"
import { errorPage, NOT_AVAILABLE_MESSAGE, SESSION_EXPIRED_MESSAGE } from "../views/error.ts"
import { pageParam } from "../utils/paging.ts"
import { orgPage } from "../views/org.ts"

export const orgPageRoutes = new OpenAPIHono()

orgPageRoutes.get("/o/:slug", async (ctx) => {
  const slug = ctx.req.param("slug") ?? ""
  const facts = await readRequestFacts(ctx)

  if (!isValidSlug(slug)) {
    return notAvailable()
  }

  // The token exchange is shared with the admin page, which the desktop panel also opens
  // directly. See routes/handoff-exchange.ts for why it cannot live here.
  const exchanged = await consumeHandoffToken(ctx, facts, slug, `/o/${encodeURIComponent(slug)}`)
  if (exchanged) return exchanged

  const session = facts.session
  if (!session) {
    return htmlResponse(
      (nonce) =>
        errorPage({
          nonce,
          title: "Session expired - BOSS",
          heading: "Session expired",
          message: SESSION_EXPIRED_MESSAGE,
        }),
      { status: 401 },
    )
  }

  // The cookie names its org. A session for one org must not render another's
  // page even if the viewer is a member of both -- the links, the forms and the
  // CSRF nonce all belong to the org in the cookie.
  if (session.slug !== slug) {
    return notAvailable()
  }

  // Live probe. The cookie is authentication; this is authorization, and it is
  // re-asked on every request so a removed member loses access immediately.
  if (!await isOrgMember(session.sub, session.org)) {
    return notAvailable()
  }

  const data = await loadOrgPageData(session.sub, session.org)
  if (!data.ok) {
    return notAvailable()
  }

  return htmlResponse((nonce) =>
    orgPage({
      nonce,
      basePath: facts.basePath,
      org: data.org,
      members: data.members,
      roles: data.roles,
      plugins: data.plugins,
      // Independent, so paging one table leaves the other where it was. Unreadable values become
      // page 1 and out-of-range ones are clamped by the view, which is the only place that knows
      // how many pages there are - so a stale bookmark lands on the last page rather than on
      // nothing, and a hand-edited URL cannot produce an error.
      membersPage: pageParam(new URL(ctx.req.url).searchParams.get("members")),
      pluginsPage: pageParam(new URL(ctx.req.url).searchParams.get("plugins")),
    })
  )
})

function notAvailable(): Response {
  return htmlResponse(
    (nonce) =>
      errorPage({
        nonce,
        title: "Not available - BOSS",
        heading: "Not available",
        message: NOT_AVAILABLE_MESSAGE,
      }),
    { status: 404 },
  )
}
