/**
 * The desktop -> web handoff exchange.
 *
 * The plugin mints a token over its AUTHENTICATED client and opens
 *   <base>/o/<slug>?t=<token>
 * in a browser tab. This module turns that token into a session cookie and
 * removes it from the URL.
 *
 * THE TOKEN IS A BEARER CREDENTIAL for its ~5 minute life. It must not appear
 * in the response body, in the `Location` header, or in any log line, not even
 * truncated -- a truncated bearer is still a large hint, and the token is
 * single-use anyway so the only reason to log it would be to debug a flow that
 * has already failed.
 */

import { callRpc } from "../utils/org-rpc.ts"

export interface HandoffIdentity {
  userId: string
  orgId: string
  orgSlug: string
  purpose: string
  email: string | null
}

export type HandoffOutcome =
  | { ok: true; identity: HandoffIdentity }
  | { ok: false; reason: "invalid" | "slug_mismatch" }

interface ConsumeResponse {
  user_id?: unknown
  org_id?: unknown
  org_slug?: unknown
  purpose?: unknown
  email?: unknown
}

/**
 * Consume a handoff token and check it belongs to the org in the URL.
 *
 * THE SLUG CROSS-CHECK. A token names the org it was minted for. Without
 * comparing that to the slug in the path, a valid token for org A opened at
 * /o/b would mint a session whose `org` is A while every link on the rendered
 * page says B -- the page would then read and write org A's data under org B's
 * URL, which is confusing at best and, if a handler ever trusted the path over
 * the cookie, a cross-org write.
 *
 * All failures collapse to one outcome for the caller to render identically:
 * unknown, expired, already-consumed and wrong-org must not be distinguishable,
 * or the endpoint reports whether a guessed token ever existed.
 */
export async function exchangeHandoffToken(
  token: string,
  expectedSlug: string,
): Promise<HandoffOutcome> {
  const result = await callRpc<ConsumeResponse>("consume_organisation_handoff_token", {
    p_token: token,
  })

  if (!result.ok) return { ok: false, reason: "invalid" }

  const payload = result.data
  const userId = typeof payload.user_id === "string" ? payload.user_id : null
  const orgId = typeof payload.org_id === "string" ? payload.org_id : null
  const orgSlug = typeof payload.org_slug === "string" ? payload.org_slug : null

  if (!userId || !orgId || !orgSlug) return { ok: false, reason: "invalid" }

  if (orgSlug !== expectedSlug) {
    // Logged without the token, and without the slug the caller asked for --
    // the pair would let log access confirm a token's org.
    console.warn("handoff token org did not match the requested organisation")
    return { ok: false, reason: "slug_mismatch" }
  }

  return {
    ok: true,
    identity: {
      userId,
      orgId,
      orgSlug,
      purpose: typeof payload.purpose === "string" ? payload.purpose : "org_view",
      email: typeof payload.email === "string" ? payload.email : null,
    },
  }
}

/**
 * The same URL with `t` removed, preserving every other parameter.
 *
 * Built from an explicitly supplied `basePath` plus the route, never from
 * `ctx.req.url`: the gateway strips `/functions/v1`, so echoing the request URL
 * would redirect the browser to a path that does not exist for it.
 *
 * The result is a path, not an absolute URL. A relative `Location` cannot be
 * turned into an open redirect by a crafted Host header.
 */
export function urlWithoutToken(basePath: string, route: string, search: URLSearchParams): string {
  const remaining = new URLSearchParams(search)
  remaining.delete("t")
  const query = remaining.toString()
  const path = `${basePath}${route.startsWith("/") ? route : `/${route}`}`
  return query.length > 0 ? `${path}?${query}` : path
}
