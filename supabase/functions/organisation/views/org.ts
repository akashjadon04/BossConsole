/**
 * The member-facing organisation overview.
 *
 * Read-only. Every mutation lives on the admin page, which keeps this view free
 * of CSRF concerns entirely.
 */

import { attrUrl, esc, scrollable } from "../utils/html.ts"
import { layout, tabs } from "./layout.ts"
import { type Paged, paginate } from "../utils/paging.ts"
import type { OrgDetail, OrgMember, OrgRole } from "../services/org.ts"
import type { OrgPluginSummary } from "../services/plugin.ts"

/**
 * Rows per page.
 *
 * Chosen so the common case has no pager at all: most organisations have fewer members than this,
 * and a control that appears for three rows is noise. It is deliberately the same for both tables -
 * two different page sizes on one page is a detail a reader has to learn for no benefit.
 */
const PAGE_SIZE = 25

export interface OrgPageOptions {
  nonce: string
  basePath: string
  org: OrgDetail
  members: OrgMember[]
  roles: OrgRole[]
  plugins: OrgPluginSummary[]
  /** Requested page for each table; out of range is clamped, not refused. */
  membersPage?: number
  pluginsPage?: number
}

export function orgPage(
  { nonce, basePath, org, members, roles, plugins, membersPage, pluginsPage }: OrgPageOptions,
): string {
  const active = members.filter((m) => m.status === "active")
  const memberPage = paginate(active, membersPage ?? 1, PAGE_SIZE)
  const pluginPage = paginate(plugins, pluginsPage ?? 1, PAGE_SIZE)

  /**
   * A link to this page with one table's page changed and the other's kept.
   *
   * Both are in the URL because they are independent: paging through members must not silently
   * send the plugins table back to page one, which is what dropping the other parameter would do.
   * A page of 1 is omitted, so the ordinary URL stays clean and a shared link is the short one.
   */
  const pageHref = (memberN: number, pluginN: number): string => {
    const params: string[] = []
    if (memberN > 1) params.push(`members=${memberN}`)
    if (pluginN > 1) params.push(`plugins=${pluginN}`)
    const query = params.length > 0 ? `?${params.join("&")}` : ""
    return `${basePath}/o/${encodeURIComponent(org.slug)}${query}`
  }

  return layout({
    title: `${org.name} - BOSS`,
    nonce,
    body: `
<header class="page">
  <h1>${esc(org.name)}</h1>
  <span class="slug">@${esc(org.slug)}</span>
  ${org.is_system ? '<span class="pill">system</span>' : ""}
</header>
<p class="sub">${esc(org.description ?? "No description.")}</p>
${
    org.website
      // attrUrl with http/https opted in. Everywhere else on these pages it is
      // called with no schemes at all, which refuses anything but a same-origin
      // path - correct for our own links, and wrong for the one field that is
      // deliberately external. It still refuses javascript:, data: and a
      // protocol-relative //host, which is what matters for a value a requester
      // supplies. rel="noreferrer" because this is the one outbound link here.
      ? `<p class="sub"><a href="${
        attrUrl(org.website, ["http", "https"])
      }" target="_blank" rel="noopener noreferrer">${esc(org.website)}</a></p>`
      : ""
  }
${tabs(basePath, org.slug, "overview", org.is_admin)}

<section class="card">
  <div class="stat"><b>${esc(org.member_count)}</b><span>members</span></div>
  <div class="stat"><b>${esc(roles.length)}</b><span>roles</span></div>
  <div class="stat phrase"><b>${esc(org.visibility)}</b><span>visibility</span></div>
  <div class="stat phrase"><b>${esc(org.join_policy.replace(/_/g, " "))}</b><span>joining</span></div>
  ${
      org.primary_domain
        ? `<div class="stat phrase"><b>${esc(org.primary_domain)}</b><span>domain</span></div>`
        : ""
    }
</section>

<section class="card">
  <h2>Members</h2>
  <p class="hint">Owned by ${esc(org.owner_email ?? "unknown")}.</p>
  ${membersTable(memberPage)}
  ${pager("Members", memberPage, (n) => pageHref(n, pluginPage.page))}
</section>

<section class="card">
  <h2>Roles</h2>
  <p class="hint">Roles are backed by the BOSS role system. Permissions are granted per role.</p>
  ${rolesTable(roles)}
</section>

<section class="card">
  <h2>Plugins</h2>
  <p class="hint">Plugins published under this organisation. Open one to read about it${
      org.is_admin ? " and set who can see it" : ""
    }.</p>
  ${pluginsTable(basePath, org.slug, pluginPage)}
  ${pager("Plugins", pluginPage, (n) => pageHref(memberPage.page, n))}
</section>`,
  })
}

/**
 * The organisation's plugins, each linking to its page.
 *
 * The plugin id goes through `encodeURIComponent` on the way into the href and `esc` on the way
 * into the text. Both are needed and they are not the same job: the ids are dotted reverse-DNS
 * strings that survive either treatment unchanged today, so a missing one would not show up by
 * looking at the page. Nothing constrains the column to that shape.
 */
function pluginsTable(basePath: string, slug: string, page: Paged<OrgPluginSummary>): string {
  if (page.total === 0) return '<p class="empty">No plugins published under this organisation.</p>'

  const rows = page.items.map((plugin) => {
    const href = `${basePath}/o/${encodeURIComponent(slug)}/plugins/${
      encodeURIComponent(plugin.plugin_id)
    }`
    return `
    <tr>
      <td><a href="${esc(href)}">${esc(plugin.display_name)}</a></td>
      <td class="mono">${esc(plugin.plugin_id)}</td>
      <td>${visibilityPill(plugin.visibility)}</td>
      <td>${
      plugin.published
        ? (plugin.verified ? '<span class="pill admin">verified</span>' : '<span class="pill">published</span>')
        : '<span class="pill">draft</span>'
    }</td>
    </tr>`
  }).join("")

  return scrollable("Plugins", `<table>
  <thead><tr><th>Plugin</th><th>Id</th><th>Visibility</th><th>Status</th></tr></thead>
  <tbody>${rows}</tbody>
</table>`)
}

/**
 * Visibility as a pill. `public` is the unremarkable case and stays plain; anything narrower is
 * the one worth seeing at a glance down the column, which is the whole reason this is a list.
 */
function visibilityPill(visibility: string): string {
  const restricted = visibility !== "public"
  return `<span class="pill${restricted ? " admin" : ""}">${esc(visibility)}</span>`
}

function membersTable(page: Paged<OrgMember>): string {
  if (page.total === 0) return '<p class="empty">No members yet.</p>'

  const rows = page.items.map((member) => `
    <tr>
      <td>${esc(member.email ?? "unknown")}</td>
      <td>${
    member.is_owner
      ? '<span class="pill admin">owner</span>'
      : member.is_admin
      ? '<span class="pill admin">admin</span>'
      : '<span class="pill">member</span>'
  }</td>
      <td>${(member.roles ?? []).map((r) => `<span class="pill mono">${esc(r)}</span>`).join("")}</td>
      <td>${esc(formatDate(member.joined_at))}</td>
    </tr>`).join("")

  return scrollable("Members", `<table>
  <thead><tr><th>Email</th><th>Standing</th><th>Roles</th><th>Joined</th></tr></thead>
  <tbody>${rows}</tbody>
</table>`)
}

/**
 * The pager under a table.
 *
 * NOT RENDERED AT ALL when everything fits on one page, which is the common case here - a control
 * that says "1 of 1" beside three rows is noise pretending to be information.
 *
 * Links, not buttons and no script. The whole page is server-rendered under a nonce-only CSP, so a
 * pager built from anything else would need JavaScript this page deliberately does not have; links
 * also mean a page can be bookmarked, opened in a new tab and read by anything that reads links.
 *
 * A boundary reads as a SPAN rather than a disabled link. A link to the page you are already on
 * looks operable and does nothing, which is the more confusing of the two.
 */
function pager(label: string, page: Paged<unknown>, href: (page: number) => string): string {
  if (page.pages <= 1) return ""

  const previous = page.page > 1
    ? `<a href="${esc(href(page.page - 1))}" rel="prev">Previous</a>`
    : `<span class="muted">Previous</span>`
  const next = page.page < page.pages
    ? `<a href="${esc(href(page.page + 1))}" rel="next">Next</a>`
    : `<span class="muted">Next</span>`

  return `
  <nav class="pager" aria-label="${esc(label)} pages">
    ${previous}
    <span class="pager-state">${esc(page.from)} to ${esc(page.to)} of ${esc(page.total)}</span>
    ${next}
  </nav>`
}

function rolesTable(roles: OrgRole[]): string {
  if (roles.length === 0) return '<p class="empty">No roles.</p>'

  const rows = roles.map((role) => `
    <tr>
      <td class="mono">${esc(role.role_name)}</td>
      <td><span class="pill${role.kind === "admin" ? " admin" : ""}">${esc(role.kind)}</span></td>
      <td>${esc(role.member_count)}</td>
      <td>${
    (role.permissions ?? []).length === 0
      ? '<span class="empty">none</span>'
      : (role.permissions ?? []).map((p) => `<span class="pill mono">${esc(p)}</span>`).join("")
  }</td>
    </tr>`).join("")

  return scrollable("Roles", `<table>
  <thead><tr><th>Role</th><th>Kind</th><th>Members</th><th>Permissions</th></tr></thead>
  <tbody>${rows}</tbody>
</table>`)
}

/**
 * Date for display, or an em-dash-free placeholder.
 *
 * Rendered as a plain ISO date rather than a locale string: the edge runtime's
 * locale is not the viewer's, so `toLocaleDateString` would silently pick one
 * and be wrong for most readers.
 */
export function formatDate(value: string | null): string {
  if (!value) return "-"
  const parsed = new Date(value)
  if (Number.isNaN(parsed.getTime())) return "-"
  return parsed.toISOString().slice(0, 10)
}
