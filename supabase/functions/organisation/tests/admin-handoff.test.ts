/**
 * The admin page's own handoff exchange.
 *
 * The desktop panel has two buttons. Open page opens `/o/<slug>?t=<token>`; Configure opens
 * `/o/<slug>/admin?t=<token>`. Only the first route ever consumed a token, so Configure fell
 * through to requireOrgSession, found no cookie and answered "Session expired". It only appeared
 * to work if you pressed Open page first, because that navigation had already left a cookie.
 *
 * Every assertion here fails against that version. The suite had 179 passing tests while the
 * button was broken, because nothing exercised a token on this route.
 */

import { assert, assertEquals } from "@std/assert"
import { app } from "../app.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"
import {
  orgDetailResponse,
  type RpcStub,
  restoreServiceClient,
  stubServiceClient,
  withTestEnv,
} from "./helpers/mocks.ts"

const BASE = "/organisation"
const SLUG = "acme"
const USER_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const ORG_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"

function setup(): { stub: RpcStub; restore: () => void } {
  const restoreEnv = withTestEnv()
  resetRateLimits()
  const stub = stubServiceClient()
  stub.responses.set("user_is_org_admin", true)
  stub.responses.set("user_is_org_member", true)
  stub.responses.set("get_organisation_detail", orgDetailResponse())
  for (
    const fn of [
      "list_organisation_members",
      "list_organisation_roles",
      "list_organisation_domains",
      "list_organisation_invites",
    ]
  ) {
    stub.responses.set(fn, { success: true, data: [] })
  }
  return {
    stub,
    restore: () => {
      restoreServiceClient()
      restoreEnv()
    },
  }
}

function validToken(stub: RpcStub, purpose = "org_admin"): void {
  stub.responses.set("consume_organisation_handoff_token", {
    success: true,
    user_id: USER_ID,
    org_id: ORG_ID,
    org_slug: SLUG,
    purpose,
    email: "admin@example.com",
  })
}

Deno.test("Configure works from a cold start: the admin route consumes its own token", async () => {
  const { stub, restore } = setup()
  try {
    validToken(stub)

    // No cookie at all. This is exactly what the desktop panel produces.
    const response = await app.request(`${BASE}/o/${SLUG}/admin?t=super-secret-token`)

    assertEquals(response.status, 302)
    const setCookie = response.headers.get("set-cookie") ?? ""
    assert(setCookie.startsWith("boss_org="), `no session cookie minted: ${setCookie}`)
  } finally {
    restore()
  }
})

Deno.test("it returns to the ADMIN page, not to the org page", async () => {
  const { stub, restore } = setup()
  try {
    validToken(stub)
    const response = await app.request(`${BASE}/o/${SLUG}/admin?t=tok`)

    // The redirect target is built from the route's own path. Sending the operator to /o/acme
    // would "work" - a cookie would exist - but Configure would land on the member overview and
    // they would have to find the link again, which is the bug with extra steps.
    assertEquals(
      response.headers.get("location") ?? "",
      "/functions/v1/organisation/o/acme/admin",
    )
  } finally {
    restore()
  }
})

Deno.test("the token does not survive into the Location or the cookie", async () => {
  const { stub, restore } = setup()
  try {
    validToken(stub)
    const response = await app.request(`${BASE}/o/${SLUG}/admin?t=super-secret-token`)

    const location = response.headers.get("location") ?? ""
    const setCookie = response.headers.get("set-cookie") ?? ""
    assertEquals(location.includes("super-secret-token"), false)
    assertEquals(setCookie.includes("super-secret-token"), false)
    // 302 rather than 303 so the ?t= URL leaves the history entry too.
    assertEquals(response.status, 302)
  } finally {
    restore()
  }
})

Deno.test("other query parameters survive the exchange, only t is dropped", async () => {
  const { stub, restore } = setup()
  try {
    validToken(stub)
    // A banner key must reach the page it was redirected to. Dropping the whole query string
    // would silently swallow every ?ok= and ?err= that arrived alongside a token.
    const response = await app.request(`${BASE}/o/${SLUG}/admin?t=tok&ok=domain_verified`)
    const location = response.headers.get("location") ?? ""
    assertEquals(location, "/functions/v1/organisation/o/acme/admin?ok=domain_verified")
  } finally {
    restore()
  }
})

Deno.test("a cross-site navigation carrying a token is refused", async () => {
  const { stub, restore } = setup()
  try {
    validToken(stub)
    const headers = new Headers()
    headers.set("sec-fetch-site", "cross-site")

    // Login CSRF: any page could otherwise navigate a victim here with an attacker's token and
    // replace their org session. The guard has to be on this route too, not only on /o/:slug.
    const response = await app.request(`${BASE}/o/${SLUG}/admin?t=tok`, { headers })
    assertEquals(response.status, 400)
    assertEquals(response.headers.get("set-cookie"), null)
  } finally {
    restore()
  }
})

Deno.test("an invalid token renders the expired page, not a session-expired one", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("consume_organisation_handoff_token", { success: false })
    const response = await app.request(`${BASE}/o/${SLUG}/admin?t=tok`)
    assertEquals(response.status, 400)
    assertEquals(response.headers.get("set-cookie"), null)
  } finally {
    restore()
  }
})

Deno.test("a token for another organisation cannot open this admin page", async () => {
  const { stub, restore } = setup()
  try {
    validToken(stub)
    stub.responses.set("consume_organisation_handoff_token", {
      success: true,
      user_id: USER_ID,
      org_id: ORG_ID,
      org_slug: "someone-else",
      purpose: "org_admin",
      email: "admin@example.com",
    })
    const response = await app.request(`${BASE}/o/${SLUG}/admin?t=tok`)
    // The slug in the path is cross-checked against the token's org, and both failure modes
    // render identically so a guessed token is not confirmed as real.
    assertEquals(response.status, 400)
    assertEquals(response.headers.get("set-cookie"), null)
  } finally {
    restore()
  }
})

Deno.test("with no token and no cookie the answer is still Session expired", async () => {
  const { restore } = setup()
  try {
    // The fix must not have turned the unauthenticated case into something else. This is what a
    // stale bookmark hits, and it should still say to open the page from BOSS.
    const response = await app.request(`${BASE}/o/${SLUG}/admin`)
    assertEquals(response.status, 401)
  } finally {
    restore()
  }
})

Deno.test("a malformed slug is refused before anything is built into a Location", async () => {
  const { stub, restore } = setup()
  try {
    validToken(stub)
    // isValidSlug runs before the exchange precisely because the slug becomes a redirect target.
    const response = await app.request(`${BASE}/o/Not%20A%20Slug/admin?t=tok`)
    assert(
      response.status === 401 || response.status === 404,
      `expected a refusal, got ${response.status}`,
    )
    assertEquals(response.headers.get("set-cookie"), null)
  } finally {
    restore()
  }
})
