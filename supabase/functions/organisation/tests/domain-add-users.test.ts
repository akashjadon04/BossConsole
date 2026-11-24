/**
 * Adopting a verified domain's existing users.
 *
 * This is the only action in the organisation surface that adds people who did not ask, so the
 * assertions are weighted towards refusal and towards the administrator seeing the size of it
 * before pressing. The database-side rules (who is skipped, confirmed addresses only, reserved
 * domains) are pinned in supabase/tests/add_domain_users_test.sql; what is checked here is the
 * route's own guards and what the page offers.
 */

import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import { adminPage } from "../views/admin.ts"
import type { OrgDetail, OrgDomain } from "../services/org.ts"

const NONCE = "nonce"

const org = {
  id: "11111111-1111-4111-8111-111111111111",
  slug: "acme",
  name: "Acme",
  description: null,
  website: null,
  visibility: "private",
  join_policy: "invite_only",
  is_system: false,
  owner_email: "owner@acme.example",
  member_count: 1,
  pending_count: 0,
  primary_domain: null,
  is_owner: true,
  is_admin: true,
  can_publish: true,
  publish_policy: "owner_only",
  publish_role_id: null,
  publish_role_name: null,
  auto_assign_member_role: true,
} as unknown as OrgDetail

function domain(overrides: Partial<OrgDomain> = {}): OrgDomain {
  return {
    domain_id: "d1",
    domain: "acme.example",
    is_primary: false,
    verified: true,
    verified_at: "2026-01-01T00:00:00Z",
    dns_record_type: "TXT",
    dns_record_name: "_boss-verify.acme.example",
    dns_record_value: "boss-org-verification=abc123",
    addable_user_count: 12,
    ...overrides,
  } as OrgDomain
}

function render(domains: OrgDomain[]): string {
  return adminPage({
    nonce: NONCE,
    basePath: "/functions/v1/organisation",
    csrf: "csrf-token",
    org,
    members: [],
    roles: [],
    domains,
    invites: [],
  })
}

Deno.test("the count is in the label, so the size is seen before pressing", () => {
  const html = render([domain({ addable_user_count: 12 })])
  // The label IS the confirmation step. This adds people who did not ask and, with auto-assign on,
  // hands each of them the member role - so a bare "Add users" would be asking for a signature on
  // an unspecified number.
  assertStringIncludes(html, "Add 12 existing users")
  assertStringIncludes(html, "/domains/add-users")
})

Deno.test("one user reads as one, not as a plural", () => {
  const html = render([domain({ addable_user_count: 1 })])
  assertStringIncludes(html, "Add 1 existing user<")
})

Deno.test("no button when there is nobody left to add", () => {
  // Otherwise every organisation that has already adopted its domain carries a permanent
  // "Add 0 users" control that does nothing.
  const html = render([domain({ addable_user_count: 0 })])
  assertEquals(html.includes("/domains/add-users"), false)
})

Deno.test("no button on an unverified domain, whatever the count says", () => {
  // Verification is the entire authority for the action. A count arriving non-zero on an
  // unverified row would be a server bug, and the view must not act on it.
  const html = render([domain({ verified: false, verified_at: null, addable_user_count: 9 })])
  assertEquals(html.includes("/domains/add-users"), false)
})

Deno.test("an older database with no count renders no button, not 'Add undefined users'", () => {
  // The function and the migration deploy separately. Against a database that has not yet gained
  // `addable_user_count`, the field is absent - and `undefined < 1` is false, so a guard that
  // trusted the type would have shown the control with a broken label and called an RPC that does
  // not exist there either.
  const stale = domain()
  delete (stale as unknown as Record<string, unknown>).addable_user_count
  const html = render([stale])
  assertEquals(html.includes("/domains/add-users"), false)
  assertEquals(html.includes("undefined"), false)
})

Deno.test("the form carries CSRF and the domain id, like every other action here", () => {
  const html = render([domain()])
  const form = html.match(/<form[^>]*\/domains\/add-users[\s\S]*?<\/form>/)
  assert(form, "no add-users form rendered")
  assertStringIncludes(form[0], `name="domain_id" value="d1"`)
  assertStringIncludes(form[0], `method="post"`)
  // A GET-able bulk membership change would be reachable from an <img> tag on any page.
  assertEquals(/method="get"/i.test(form[0]), false)
})

Deno.test("the consequence is stated on the page, not only in the migration", () => {
  const html = render([domain()])
  // The non-obvious half: members arrive without being asked, and auto-assign then makes anything
  // shared with the member role readable by all of them.
  assertStringIncludes(html, "without being asked")
  assertStringIncludes(html, "acme_user")
})

Deno.test("the route calls the RPC through callForActor, so it is actor-gated", async () => {
  const source = await Deno.readTextFile(
    new URL("../routes/domains.ts", import.meta.url).pathname,
  )
  // p_actor_id is what makes the RPC's own user_is_org_admin check meaningful. Calling it as bare
  // service_role would run the whole thing as nobody, and the admin gate would have no subject.
  assert(
    /callForActor\(\s*"add_domain_users_to_organisation"/.test(source),
    "the adoption RPC must be called for the session's actor, never as bare service_role",
  )
})

Deno.test("the route refuses an unverified domain before reaching the database", () => {
  // Cheap, and it turns what would be a generic refusal into a state the page can explain. The
  // RPC re-checks because it is reachable by `authenticated` directly.
  const source = Deno.readTextFileSync(
    new URL("../routes/domains.ts", import.meta.url).pathname,
  )
  const handler = source.slice(source.indexOf('/domains/add-users"'))
  assertStringIncludes(handler.slice(0, 800), "!owned || !owned.verified")
})
