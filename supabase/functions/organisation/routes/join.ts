/**
 * GET /join/:token -- the invite landing page.
 *
 * A NEW ROUTE RATHER THAN A BRANCH IN `redirect`. Folding this into the
 * existing redirect function would put an org-invite token and a GoTrue email
 * token hash on the same `?token=` parameter, both funnelling toward
 * `boss://auth/verify`. Those are different credentials with different
 * lifetimes and different consequences for being confused, and that function
 * documents a "pure redirect, no data access" invariant this page would break
 * by calling an RPC.
 *
 * This page does NOT redeem. See views/join.ts for why the prefetch property
 * that buys is worth more than the extra click.
 */

import { OpenAPIHono } from "@hono/zod-openapi"
import { callRpc } from "../utils/org-rpc.ts"
import { htmlResponse } from "../utils/responses.ts"
import { clientKey, rateLimit } from "../utils/rate-limit.ts"
import { deepLinkScheme } from "../utils/config.ts"
import { attrUrl } from "../utils/html.ts"
import { invalidInvitePage, joinPage } from "../views/join.ts"

const PREVIEW_LIMIT = 30
const PREVIEW_WINDOW_SECONDS = 60

/**
 * Invite tokens are minted as `boss_inv_` + 43 url-safe base64 characters.
 * Anything not shaped like one is refused without a database round trip.
 */
const TOKEN_PATTERN = /^boss_inv_[A-Za-z0-9_-]{40,64}$/

export const joinRoutes = new OpenAPIHono()

interface InvitePreview {
  valid?: unknown
  name?: unknown
  slug?: unknown
  description?: unknown
}

joinRoutes.get("/join/:token", async (ctx) => {
  const token = ctx.req.param("token")

  const limit = rateLimit(
    `invite:${clientKey(ctx.req.raw.headers)}`,
    PREVIEW_LIMIT,
    PREVIEW_WINDOW_SECONDS,
  )
  // A rate-limited caller gets the SAME page an invalid token gets. A distinct
  // 429 would tell a script it was probing fast enough to matter, and would
  // separate "wrong" from "too many", which is a signal on its own.
  if (!limit.allowed || !TOKEN_PATTERN.test(token)) {
    return htmlResponse((nonce) => invalidInvitePage(nonce), { status: 404 })
  }

  const result = await callRpc<InvitePreview>("get_organisation_invite_preview", {
    p_token: token,
  })

  // Unknown, expired, revoked, exhausted and "the database is down" all render
  // one page with one status. The endpoint must not be an invite oracle.
  if (!result.ok || result.data.valid !== true) {
    return htmlResponse((nonce) => invalidInvitePage(nonce), { status: 404 })
  }

  const name = typeof result.data.name === "string" ? result.data.name : "an organisation"
  const slug = typeof result.data.slug === "string" ? result.data.slug : ""
  const description = typeof result.data.description === "string"
    ? result.data.description
    : null

  // The token goes into the deep link because the APP is what redeems it. It
  // stays out of the page text, out of the log, and out of any other link.
  const scheme = deepLinkScheme()
  const deepLink = attrUrl(
    `${scheme}://organisation/join?token=${encodeURIComponent(token)}`,
    [scheme],
  )

  return htmlResponse((nonce) =>
    joinPage({ nonce, orgName: name, orgSlug: slug, description, deepLink })
  )
})
