/**
 * The plugin page, driven through app.request().
 *
 * These exist because the page is now readable WITHOUT a session, which is the only reason the
 * Toolbox's Store, Installed and Updates lists can link to it: minting a handoff needs
 * membership, and almost no reader is a member of the organisations that own the store's plugins.
 *
 * Widening who may read a page deserves tests at the route, not at the view. What is asserted
 * here is that the widening is exactly "the RPC decides, with a null viewer" - not "anyone sees
 * anything", and not "the control is drawn for a stranger".
 */

import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import { app } from "../app.ts"
import { mintSession } from "../utils/session.ts"
import { resetRateLimits } from "../utils/rate-limit.ts"
import {
  FIXTURE,
  restoreServiceClient,
  type RpcStub,
  stubServiceClient,
  withTestEnv,
} from "./helpers/mocks.ts"

const BASE = "/organisation"
const PLUGIN_ID = "ai.rever.boss.plugin.dynamic.codexglm"

/** The row shape get_plugin_with_stats_for_viewer returns: an ARRAY, it is set-returning. */
function pluginRows(overrides: Record<string, unknown> = {}) {
  return [{
    id: "77777777-7777-4777-8777-777777777777",
    plugin_id: PLUGIN_ID,
    display_name: "Codex GLM",
    description: "An AI provider",
    author_name: "Risa Labs",
    homepage_url: null,
    icon_url: null,
    type: "panel",
    api_version: "1.0.75",
    verified: true,
    published: true,
    visibility: "public",
    org_id: FIXTURE.orgId,
    org_slug: FIXTURE.slug,
    download_count: 12,
    latest_version: "1.0.4",
    updated_at: "2026-08-01T00:00:00Z",
    ...overrides,
  }]
}

function setup(): { stub: RpcStub; restore: () => void } {
  const restoreEnv = withTestEnv()
  resetRateLimits()
  const stub = stubServiceClient()
  stub.responses.set("get_plugin_with_stats_for_viewer", pluginRows())
  stub.responses.set("user_is_org_admin", true)
  stub.responses.set("user_is_org_member", true)
  return {
    stub,
    restore: () => {
      restoreServiceClient()
      restoreEnv()
    },
  }
}

async function cookie(slug = FIXTURE.slug, org = FIXTURE.orgId): Promise<string> {
  const value = await mintSession({ sub: FIXTURE.userId, org, slug, csrf: "test-csrf-nonce", pur: "org_admin" })
  return `boss_org=${value}`
}

function get(path: string, headers: Record<string, string> = {}) {
  return app.request(`http://localhost${BASE}${path}`, { headers })
}

const PATH = `/o/${FIXTURE.slug}/plugins/${PLUGIN_ID}`

// ---------------------------------------------------------------------------
// Signed out
// ---------------------------------------------------------------------------

Deno.test("a signed-out reader gets a public plugin's page", async () => {
  const { stub, restore } = setup()
  try {
    const response = await get(PATH)
    assertEquals(response.status, 200)
    const html = await response.text()
    assertStringIncludes(html, "Codex GLM")

    // The widening in one assertion: the viewer really is anonymous, so the RPC's own
    // public-and-published arm is what let this through, not a bypass in TypeScript.
    const call = stub.calls.find((c) => c.fn === "get_plugin_with_stats_for_viewer")
    assert(call, "the page did not read the plugin through the RPC")
    assertEquals(call.params.p_viewer_id, null)
  } finally {
    restore()
  }
})

Deno.test("a signed-out reader gets no control and no form", async () => {
  const { restore } = setup()
  try {
    const html = await (await get(PATH)).text()
    assertEquals(html.includes("<form"), false)
    assertEquals(html.includes('name="visibility"'), false)
    assertStringIncludes(html, "administrators can change it")
  } finally {
    restore()
  }
})

Deno.test("a plugin the RPC does not return is not available, signed out", async () => {
  // How an `org` or `unlisted` plugin arrives here for an anonymous reader: no rows.
  const { stub, restore } = setup()
  try {
    stub.responses.set("get_plugin_with_stats_for_viewer", [])
    assertEquals((await get(PATH)).status, 404)
  } finally {
    restore()
  }
})

Deno.test("the organisation in the path must own the plugin", async () => {
  // Without this a public plugin would render under ANY organisation's URL, which would make the
  // page lie about ownership - the one thing nesting it under the org was supposed to make plain.
  const { stub, restore } = setup()
  try {
    stub.responses.set("get_plugin_with_stats_for_viewer", pluginRows({ org_slug: "someone-else" }))
    assertEquals((await get(PATH)).status, 404)
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// Signed in
// ---------------------------------------------------------------------------

Deno.test("an admin of the owning organisation gets the control", async () => {
  const { restore } = setup()
  try {
    const html = await (await get(PATH, { cookie: await cookie() })).text()
    assertStringIncludes(html, 'name="visibility"')
    assertStringIncludes(html, `${PLUGIN_ID}/visibility`)
  } finally {
    restore()
  }
})

Deno.test("a member who is not an admin gets the page without the control", async () => {
  const { stub, restore } = setup()
  try {
    stub.responses.set("user_is_org_admin", false)
    const html = await (await get(PATH, { cookie: await cookie() })).text()
    assertStringIncludes(html, "Codex GLM")
    assertEquals(html.includes('name="visibility"'), false)
  } finally {
    restore()
  }
})

Deno.test("a session for another organisation is treated as no session, not as an error", async () => {
  // It is not this page's business to refuse a stranger who happens to be signed in elsewhere.
  // They get exactly what a signed-out reader gets, decided by the same null-viewer RPC call.
  const { stub, restore } = setup()
  try {
    const other = await cookie("other-org", FIXTURE.otherOrgId)
    const response = await get(PATH, { cookie: other })
    assertEquals(response.status, 200)
    const call = stub.calls.find((c) => c.fn === "get_plugin_with_stats_for_viewer")
    assertEquals(call?.params.p_viewer_id, null)
    assertEquals((await response.text()).includes('name="visibility"'), false)
  } finally {
    restore()
  }
})

Deno.test("a signed-in viewer's own id is what the RPC is asked about", async () => {
  const { stub, restore } = setup()
  try {
    await get(PATH, { cookie: await cookie() })
    const call = stub.calls.find((c) => c.fn === "get_plugin_with_stats_for_viewer")
    assertEquals(call?.params.p_viewer_id, FIXTURE.userId)
  } finally {
    restore()
  }
})

// ---------------------------------------------------------------------------
// The write is not widened
// ---------------------------------------------------------------------------

Deno.test("POST visibility still refuses a signed-out caller", async () => {
  const { stub, restore } = setup()
  try {
    const response = await app.request(`http://localhost${BASE}${PATH}/visibility`, {
      method: "POST",
      headers: { "content-type": "application/x-www-form-urlencoded" },
      body: "visibility=org",
    })
    assert(response.status >= 400, `expected a refusal, got ${response.status}`)
    assertEquals(stub.calls.some((c) => c.fn === "set_plugin_visibility"), false)
  } finally {
    restore()
  }
})
