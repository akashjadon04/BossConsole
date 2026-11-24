/**
 * Which organisation a new plugin is attributed to, and who is allowed to say.
 *
 * The publish path runs as SERVICE ROLE, so every RLS check on `plugins` - including the
 * `can_publish_org_plugin` WITH CHECK - is bypassed. That makes `resolvePublishOrg` the only thing
 * standing between a request body and an organisation it has no rights in, so the refusals matter
 * more than the happy path.
 *
 * Note what is deliberately NOT symmetric: an organisation the caller NAMED and may not publish for
 * is a 403, while one merely DERIVED from their memberships falls back to the default instead of
 * failing. Asking for something you cannot have is an error; not asking is not.
 */

import { assert, assertEquals } from "@std/assert"
import type { SupabaseClient } from "@supabase/supabase-js"
import { resolvePublishOrg } from "../services/publish-org.ts"
import type { PublishOrgResolution } from "../services/publish-org.ts"
import type { AuthResult } from "../utils/auth.ts"

const USER = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"
const ORG_RISA = "11111111-1111-4111-8111-111111111111"
const ORG_OTHER = "22222222-2222-4222-8222-222222222222"
const ORG_BOSS = "33333333-3333-4333-8333-333333333333"

interface StubOpts {
  /** Answer for user_can_publish_org_plugin, per org id. Absent org id answers false. */
  canPublish?: Record<string, boolean>
  /** Rows for get_my_organisations, already in envelope `data` shape. */
  orgs?: Array<Record<string, unknown>>
  /** Function names that should return an error instead of data. */
  failing?: string[]
}

function stub(opts: StubOpts = {}): { client: SupabaseClient; calls: string[] } {
  const calls: string[] = []
  const client = {
    rpc: (fn: string, args: Record<string, unknown>) => {
      calls.push(fn)
      if (opts.failing?.includes(fn)) {
        return Promise.resolve({ data: null, error: { message: "boom" } })
      }
      if (fn === "user_can_publish_org_plugin") {
        const orgId = args.p_org_id as string
        return Promise.resolve({ data: opts.canPublish?.[orgId] === true, error: null })
      }
      if (fn === "get_my_organisations") {
        return Promise.resolve({
          data: { success: true, data: opts.orgs ?? [] },
          error: null,
        })
      }
      return Promise.resolve({ data: null, error: { message: `unexpected rpc ${fn}` } })
    },
  }
  return { client: client as unknown as SupabaseClient, calls }
}

function user(extra: Partial<AuthResult> = {}): AuthResult {
  return { userId: USER, email: "a@b.test", isAdmin: false, jwtPermissions: null, ...extra }
}

/**
 * Narrowing helpers.
 *
 * `assert(result.ok)` from @std/assert declares `asserts expr`, which says the EXPRESSION is truthy
 * and narrows nothing about `result` - so `result.orgId` stays a type error on the union. These
 * discriminate properly, and their throw messages say what was actually returned, which is more
 * useful than a bare assertion failure.
 */
function orgOf(result: PublishOrgResolution): string | null {
  if (!result.ok) throw new Error(`expected success, got ${result.status}: ${result.error}`)
  return result.orgId
}

function refusalOf(result: PublishOrgResolution): { status: number; error: string } {
  if (result.ok) throw new Error(`expected a refusal, got orgId=${result.orgId}`)
  return { status: result.status, error: result.error }
}

// ---------------------------------------------------------------------------
// An explicitly requested organisation
// ---------------------------------------------------------------------------

Deno.test("a requested organisation the caller may publish for is used", async () => {
  const { client, calls } = stub({ canPublish: { [ORG_RISA]: true } })
  const result = await resolvePublishOrg(client, user(), ORG_RISA)
  assertEquals(orgOf(result), ORG_RISA)
  // Authorised, not trusted. Service role bypasses the RLS WITH CHECK, so if this call is ever
  // dropped nothing else refuses.
  assert(calls.includes("user_can_publish_org_plugin"))
})

Deno.test("a requested organisation the caller may NOT publish for is a 403", async () => {
  const { client } = stub({ canPublish: { [ORG_RISA]: false } })
  const result = await resolvePublishOrg(client, user(), ORG_RISA)
  assertEquals(refusalOf(result).status, 403)
})

Deno.test("a refused request does not quietly fall back to a derived organisation", async () => {
  // The dangerous shape: refuse the named org, then publish somewhere else anyway. The caller
  // would get a 201 and a plugin attributed to an organisation they did not choose.
  const { client } = stub({
    canPublish: { [ORG_RISA]: false, [ORG_OTHER]: true },
    orgs: [{ id: ORG_OTHER, is_system: false, status: "active" }],
  })
  const result = await resolvePublishOrg(client, user(), ORG_RISA)
  refusalOf(result)
})

Deno.test("an unanswerable permission check fails closed, it does not default", async () => {
  const { client } = stub({ failing: ["user_can_publish_org_plugin"] })
  const result = await resolvePublishOrg(client, user(), ORG_RISA)
  assertEquals(refusalOf(result).status, 500)
})

// ---------------------------------------------------------------------------
// The API key's own organisation
// ---------------------------------------------------------------------------

Deno.test("an API key publishes for the organisation it is bound to", async () => {
  // The field validate_plugin_api_key has always returned and the function used to discard.
  const { client } = stub({ canPublish: { [ORG_RISA]: true } })
  const result = await resolvePublishOrg(client, user({ apiKeyOrgId: ORG_RISA }))
  assertEquals(orgOf(result), ORG_RISA)
})

Deno.test("an API key whose owner lost publishing rights is refused", async () => {
  // The key is not an independent principal. Revoking the human's membership has to revoke their
  // CI key's publish rights too, which is the RPC's own documented contract.
  const { client } = stub({ canPublish: { [ORG_RISA]: false } })
  const result = await resolvePublishOrg(client, user({ apiKeyOrgId: ORG_RISA }))
  assertEquals(refusalOf(result).status, 403)
})

Deno.test("an explicit request outranks the API key's organisation", async () => {
  const { client } = stub({ canPublish: { [ORG_OTHER]: true } })
  const result = await resolvePublishOrg(client, user({ apiKeyOrgId: ORG_RISA }), ORG_OTHER)
  assertEquals(orgOf(result), ORG_OTHER)
})

// ---------------------------------------------------------------------------
// Derived from membership
// ---------------------------------------------------------------------------

Deno.test("a single non-system organisation is used when nothing was named", async () => {
  const { client } = stub({
    canPublish: { [ORG_RISA]: true },
    orgs: [
      { id: ORG_BOSS, is_system: true, status: "active" },
      { id: ORG_RISA, is_system: false, status: "active" },
    ],
  })
  const result = await resolvePublishOrg(client, user())
  assertEquals(orgOf(result), ORG_RISA)
})

Deno.test("the system organisation alone derives nothing", async () => {
  // Every user is an active member of @boss, so counting it would make the derived answer "boss"
  // for everybody and the whole step pointless. Returning null lets the trigger do it instead,
  // which is the same outcome by a route that is visibly a default.
  const { client } = stub({ orgs: [{ id: ORG_BOSS, is_system: true, status: "active" }] })
  const result = await resolvePublishOrg(client, user())
  assertEquals(orgOf(result), null)
})

Deno.test("two candidate organisations derive nothing rather than guessing", async () => {
  // Picking one would attribute somebody's plugin to a place they did not choose and hand that
  // organisation's admins rights over it.
  const { client } = stub({
    canPublish: { [ORG_RISA]: true, [ORG_OTHER]: true },
    orgs: [
      { id: ORG_RISA, is_system: false, status: "active" },
      { id: ORG_OTHER, is_system: false, status: "active" },
    ],
  })
  const result = await resolvePublishOrg(client, user())
  assertEquals(orgOf(result), null)
})

Deno.test("a pending membership is not a candidate", async () => {
  // Awaiting approval is not membership. Otherwise applying to an organisation would be enough to
  // publish under its name.
  const { client } = stub({
    canPublish: { [ORG_RISA]: true },
    orgs: [{ id: ORG_RISA, is_system: false, status: "pending" }],
  })
  const result = await resolvePublishOrg(client, user())
  assertEquals(orgOf(result), null)
})

Deno.test("a derived organisation the caller cannot publish for falls back, not 403", async () => {
  // The asymmetry: they never asked for it. An organisation with publish_policy = 'owner_only'
  // must not have every member's plugins land on it, but nor should membership alone turn a
  // perfectly valid publish into an error.
  const { client } = stub({
    canPublish: { [ORG_RISA]: false },
    orgs: [{ id: ORG_RISA, is_system: false, status: "active" }],
  })
  const result = await resolvePublishOrg(client, user())
  assertEquals(orgOf(result), null)
})

Deno.test("an unreadable membership list derives nothing", async () => {
  const { client } = stub({ failing: ["get_my_organisations"] })
  const result = await resolvePublishOrg(client, user())
  assertEquals(orgOf(result), null)
})

Deno.test("no memberships at all derives nothing", async () => {
  const { client } = stub({ orgs: [] })
  const result = await resolvePublishOrg(client, user())
  assertEquals(orgOf(result), null)
})

Deno.test("a blank requested organisation is treated as absent, not as an id", async () => {
  // A client sending `orgId: ""` must not produce a lookup for the empty string, which would come
  // back false and 403 a publish that named nothing.
  const { client } = stub({ orgs: [] })
  const result = await resolvePublishOrg(client, user(), "   ")
  assertEquals(orgOf(result), null)
})
