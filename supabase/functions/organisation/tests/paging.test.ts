/**
 * Paging the organisation page's tables.
 *
 * The page number comes from a query string, so it can be anything: a word, a negative, a page
 * past the end after somebody leaves the organisation. Every one of those must produce a readable
 * page rather than an error or an empty table, which is why paginate CLAMPS instead of validating.
 */

import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import { pageParam, paginate } from "../utils/paging.ts"
import { orgPage } from "../views/org.ts"
import type { OrgDetail, OrgMember } from "../services/org.ts"
import type { OrgPluginSummary } from "../services/plugin.ts"

const N = (count: number) => Array.from({ length: count }, (_, i) => i + 1)

// ---------------------------------------------------------------------------
// The primitive
// ---------------------------------------------------------------------------

Deno.test("a page is sliced, counted and described", () => {
  const p = paginate(N(120), 2, 25)
  assertEquals(p.items, [26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47, 48, 49, 50])
  assertEquals([p.page, p.pages, p.total, p.from, p.to], [2, 5, 120, 26, 50])
})

Deno.test("the last page is short, and says so", () => {
  const p = paginate(N(52), 3, 25)
  assertEquals(p.items.length, 2)
  assertEquals([p.from, p.to, p.total], [51, 52, 52])
})

Deno.test("a page past the end lands on the last page, not on nothing", () => {
  // The stale-bookmark case: somebody leaves and page 5 no longer exists.
  const p = paginate(N(30), 99, 25)
  assertEquals(p.page, 2)
  assertEquals(p.items.length, 5)
})

Deno.test("nonsense page numbers become page one rather than an error", () => {
  for (const requested of [0, -3, Number.NaN, Number.POSITIVE_INFINITY * 0]) {
    assertEquals(paginate(N(30), requested, 25).page, 1)
  }
})

Deno.test("an empty list is page 1 of 1, not 1 of 0", () => {
  const p = paginate([], 1, 25)
  assertEquals([p.page, p.pages, p.total, p.from, p.to], [1, 1, 0, 0, 0])
})

Deno.test("a query value is read leniently, because a browser and a human both send junk", () => {
  assertEquals(pageParam(null), 1)
  assertEquals(pageParam(""), 1)
  assertEquals(pageParam("abc"), 1)
  assertEquals(pageParam("-2"), 1)
  assertEquals(pageParam("0"), 1)
  assertEquals(pageParam("3"), 3)
  // Out of range is left for paginate, which is the only thing that knows how many pages exist.
  assertEquals(pageParam("9999"), 9999)
})

// ---------------------------------------------------------------------------
// The rendered pager
// ---------------------------------------------------------------------------

function org(overrides: Partial<OrgDetail> = {}): OrgDetail {
  return {
    id: "22222222-2222-4222-8222-222222222222",
    slug: "risa",
    name: "RISA Labs",
    description: null,
    website: null,
    visibility: "private",
    join_policy: "invite_only",
    publish_policy: "admins",
    member_count: 0,
    is_admin: true,
    is_system: false,
    owner_email: "a@b.test",
    primary_domain: null,
    ...overrides,
  } as OrgDetail
}

const member = (n: number): OrgMember => ({
  user_id: `u${n}`,
  email: `m${n}@x.test`,
  status: "active",
  is_owner: false,
  is_admin: false,
  roles: [],
  joined_at: null,
  requested_at: null,
  request_message: null,
  join_source: null,
} as OrgMember)

const plugin = (n: number): OrgPluginSummary => ({
  plugin_id: `p.${n}`,
  display_name: `P${n}`,
  description: null,
  icon_url: null,
  visibility: "public",
  published: true,
  verified: false,
})

function render(members: OrgMember[], plugins: OrgPluginSummary[], membersPage = 1, pluginsPage = 1) {
  return orgPage({
    nonce: "n",
    basePath: "/functions/v1/organisation",
    org: org(),
    members,
    roles: [],
    plugins,
    membersPage,
    pluginsPage,
  })
}

Deno.test("no pager at all when everything fits on one page", () => {
  // A control reading "1 of 1" beside three rows is noise pretending to be information.
  const html = render(N(3).map(member), N(2).map(plugin))
  assertEquals(html.includes('class="pager"'), false)
})

Deno.test("a long list gets a pager that says where you are", () => {
  const html = render(N(60).map(member), [])
  assertStringIncludes(html, 'aria-label="Members pages"')
  assertStringIncludes(html, "1 to 25 of 60")
})

Deno.test("only one page of rows is rendered", () => {
  const html = render(N(60).map(member), [])
  assertStringIncludes(html, "m1@x.test")
  assertStringIncludes(html, "m25@x.test")
  assertEquals(html.includes("m26@x.test"), false)
})

Deno.test("paging one table does not reset the other", () => {
  // The interaction that is easy to lose: both page numbers travel in every link, so moving
  // through members leaves the plugins table where it was.
  const html = render(N(60).map(member), N(60).map(plugin), 1, 2)
  assertStringIncludes(html, "?members=2&amp;plugins=2")
})

Deno.test("page one is left out of the link, so the ordinary url stays clean", () => {
  const html = render(N(60).map(member), [], 2, 1)
  // Going back to members page 1 with plugins on page 1 is the bare page.
  assertStringIncludes(html, 'href="/functions/v1/organisation/o/risa"')
})

Deno.test("a boundary is a span, not a link that does nothing", () => {
  const first = render(N(60).map(member), [], 1)
  assertStringIncludes(first, '<span class="muted">Previous</span>')
  const last = render(N(60).map(member), [], 3)
  assertStringIncludes(last, '<span class="muted">Next</span>')
  assertStringIncludes(last, "51 to 60 of 60")
})

Deno.test("an out-of-range page renders the last one rather than an empty table", () => {
  const html = render(N(60).map(member), [], 99)
  assertStringIncludes(html, "51 to 60 of 60")
  assertStringIncludes(html, "m60@x.test")
})

Deno.test("the pager needs no script, so the nonce-only CSP cannot break it", () => {
  const html = render(N(60).map(member), [])
  const pager = html.slice(html.indexOf('class="pager"'))
  assertEquals(/<button|onclick=/i.test(pager.slice(0, 400)), false)
  assert(pager.includes("<a href="))
})
