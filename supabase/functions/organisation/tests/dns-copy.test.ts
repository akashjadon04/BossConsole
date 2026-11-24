/**
 * The copyable DNS record.
 *
 * The point of the feature is that a person can put the record into a registrar without retyping
 * it, so the assertions are about the two things that would silently defeat that: the value being
 * split across the wrong elements, and the script that does the copying being blocked or absent.
 */

import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import { adminPage } from "../views/admin.ts"
import { layout } from "../views/layout.ts"
import type { OrgDetail, OrgDomain } from "../services/org.ts"

const NONCE = "test-nonce-value"

const org: OrgDetail = {
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
  auto_assign_member_role: false,
} as unknown as OrgDetail

function domain(overrides: Partial<OrgDomain> = {}): OrgDomain {
  return {
    domain_id: "d1",
    domain: "acme.example",
    is_primary: false,
    verified: false,
    verified_at: null,
    dns_record_type: "TXT",
    dns_record_name: "_boss-verify.acme.example",
    dns_record_value: "boss-org-verification=abc123",
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

Deno.test("the name and the value are separately copyable", () => {
  const html = render([domain()])

  // Separate buttons, because every registrar asks for host and value in different inputs. One
  // button carrying "name TXT value" would be the same unusable blob as the old plain span.
  assertStringIncludes(html, `data-copy="_boss-verify.acme.example"`)
  assertStringIncludes(html, `data-copy="boss-org-verification=abc123"`)
})

Deno.test("the copy button carries the value in the attribute, not only as text", () => {
  const html = render([domain()])
  // What gets copied must be the record, not what happens to be painted: the cell wraps long
  // values, and a future truncation would otherwise start handing out broken TXT records.
  const button = html.match(/<button[^>]*data-copy="boss-org-verification=abc123"[^>]*>/)
  assert(button, "no copy button for the record value")
  assertStringIncludes(button[0], `type="button"`)
})

Deno.test("copy buttons cannot submit the forms they sit beside", () => {
  const html = render([domain()])
  // The domains table holds POST forms for verify, primary and remove. A button defaulting to
  // submit would put "remove this domain" one stray Enter away from a copy control.
  for (const match of html.matchAll(/<button[^>]*data-copy=[^>]*>/g)) {
    assertStringIncludes(match[0], `type="button"`, `copy button is not type=button: ${match[0]}`)
  }
})

Deno.test("a verified domain shows no record to copy", () => {
  const html = render([domain({ verified: true, verified_at: "2026-01-01T00:00:00Z" })])
  assertEquals(html.includes("data-copy="), false)
})

Deno.test("a hostile domain name cannot break out of the copy attribute", () => {
  // dns_record_name derives from the organisation-supplied domain, so it reaches an HTML
  // attribute. Unescaped, a quote closes data-copy and everything after it is markup.
  const html = render([
    domain({ dns_record_name: `_boss-verify."><img src=x onerror=alert(1)>.acme.example` }),
  ])

  // Assert the STRUCTURE is dead, not that a substring is absent. `onerror=alert(1)` carries no
  // HTML metacharacter, so it survives escaping as inert text and is still present in the
  // document - the first version of this test asserted it was gone and failed against correct
  // code. What has to be impossible is the quote-then-tag sequence that ends the attribute.
  assertEquals(html.includes(`"><img`), false)
  assertEquals(/<img[^>]*onerror/.test(html), false)
  assertStringIncludes(html, "&quot;&gt;&lt;img")
})

Deno.test("the admin page carries the copy script, nonced", () => {
  const html = render([domain()])
  // The CSP is script-src 'nonce-...' with no unsafe-inline. A block without the nonce is dropped
  // by the browser and the buttons do nothing at all, which is the exact failure this asserts.
  assertStringIncludes(html, `<script nonce="${NONCE}">`)
  assertStringIncludes(html, "data-copy")
  assertStringIncludes(html, "navigator.clipboard")
})

Deno.test("the script falls back when the clipboard API is unavailable", () => {
  const html = render([domain()])
  // navigator.clipboard is undefined outside a secure context and its promise rejects when the
  // document is not focused. Without the fallback the button silently does nothing in exactly the
  // situations a person would then blame on the record.
  assertStringIncludes(html, "execCommand")
})

Deno.test("a page with nothing to copy carries no script at all", () => {
  const html = layout({ title: "t", nonce: NONCE, body: "<p>nothing</p>" })
  assertEquals(html.includes("<script"), false)
})
