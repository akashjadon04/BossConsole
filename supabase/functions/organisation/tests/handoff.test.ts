/**
 * The handoff exchange and the URL rewrite that strips the token.
 */

import { assert, assertEquals } from "@std/assert"
import { exchangeHandoffToken, urlWithoutToken } from "../services/handoff.ts"
import { FIXTURE, restoreServiceClient, stubServiceClient, withTestEnv } from "./helpers/mocks.ts"

const BASE = "/functions/v1/organisation"

Deno.test("urlWithoutToken removes t and keeps everything else", () => {
  assertEquals(
    urlWithoutToken(BASE, "/o/acme", new URLSearchParams("t=secret")),
    `${BASE}/o/acme`,
  )
  assertEquals(
    urlWithoutToken(BASE, "/o/acme", new URLSearchParams("t=secret&tab=members")),
    `${BASE}/o/acme?tab=members`,
  )
  assertEquals(
    urlWithoutToken(BASE, "/o/acme", new URLSearchParams("")),
    `${BASE}/o/acme`,
  )
})

Deno.test("urlWithoutToken never leaks the token into the Location", () => {
  const location = urlWithoutToken(
    BASE,
    "/o/acme",
    new URLSearchParams("t=boss_handoff_supersecret&x=1"),
  )
  assertEquals(location.includes("boss_handoff_supersecret"), false)
  assertEquals(location.includes("t="), false)
})

Deno.test("urlWithoutToken builds from the configured base, not the request", () => {
  // The gateway strips /functions/v1 before the function sees the URL, so a
  // Location echoed from ctx.req.url would send the browser somewhere it
  // cannot reach.
  assert(urlWithoutToken(BASE, "/o/acme", new URLSearchParams()).startsWith("/functions/v1/"))
  // Relative, so a crafted Host header cannot turn it into an open redirect.
  assertEquals(urlWithoutToken(BASE, "/o/acme", new URLSearchParams()).startsWith("//"), false)
})

Deno.test("a valid token yields the identity it names", async () => {
  const restore = withTestEnv()
  const stub = stubServiceClient()
  try {
    stub.responses.set("consume_organisation_handoff_token", {
      success: true,
      user_id: FIXTURE.userId,
      org_id: FIXTURE.orgId,
      org_slug: FIXTURE.slug,
      purpose: "org_admin",
      email: "a@example.com",
    })

    const outcome = await exchangeHandoffToken("tok", FIXTURE.slug)
    assert(outcome.ok)
    assertEquals(outcome.identity.userId, FIXTURE.userId)
    assertEquals(outcome.identity.orgId, FIXTURE.orgId)
    assertEquals(outcome.identity.purpose, "org_admin")
  } finally {
    restoreServiceClient()
    restore()
  }
})

Deno.test("a token for another organisation is refused", async () => {
  const restore = withTestEnv()
  const stub = stubServiceClient()
  try {
    stub.responses.set("consume_organisation_handoff_token", {
      success: true,
      user_id: FIXTURE.userId,
      org_id: FIXTURE.otherOrgId,
      org_slug: "othercorp",
      purpose: "org_view",
      email: null,
    })

    // A valid token opened at the wrong slug must not mint a session, or the
    // page would read and write org A under org B's URL.
    const outcome = await exchangeHandoffToken("tok", FIXTURE.slug)
    assertEquals(outcome.ok, false)
  } finally {
    restoreServiceClient()
    restore()
  }
})

Deno.test("an unusable token is refused", async () => {
  const restore = withTestEnv()
  const stub = stubServiceClient()
  try {
    stub.responses.set("consume_organisation_handoff_token", {
      success: false,
      error: "Token is invalid, expired or already used",
    })
    assertEquals((await exchangeHandoffToken("tok", FIXTURE.slug)).ok, false)

    // A transport failure is refused the same way, rather than throwing.
    stub.responses.delete("consume_organisation_handoff_token")
    stub.errors.set("consume_organisation_handoff_token", "connection reset")
    assertEquals((await exchangeHandoffToken("tok", FIXTURE.slug)).ok, false)
  } finally {
    restoreServiceClient()
    restore()
  }
})

Deno.test("an envelope missing its identity fields is refused", async () => {
  const restore = withTestEnv()
  const stub = stubServiceClient()
  try {
    stub.responses.set("consume_organisation_handoff_token", {
      success: true,
      org_slug: FIXTURE.slug,
      // no user_id, no org_id
    })
    assertEquals((await exchangeHandoffToken("tok", FIXTURE.slug)).ok, false)
  } finally {
    restoreServiceClient()
    restore()
  }
})
