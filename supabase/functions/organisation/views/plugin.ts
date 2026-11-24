/**
 * One plugin's page.
 *
 * Reuses the org pages' layout, so the palette, contrast work and light/dark handling are the
 * ones tests/contrast.test.ts already asserts. Nothing here introduces a colour.
 */

import { attrUrl, esc, scrollable } from "../utils/html.ts"
import { renderMarkdown } from "../services/markdown.ts"
import { csrfField, layout } from "./layout.ts"
import { CSRF_FIELD } from "../utils/csrf.ts"
import type { PluginDetail } from "../services/plugin.ts"

export interface PluginPageOptions {
  nonce: string
  basePath: string
  orgSlug: string
  csrf: string
  plugin: PluginDetail
  /** README text, already fetched. Null when there is none to show. */
  readme: string | null
  /** Whether to render the visibility control. The RPC re-checks regardless. */
  canEdit: boolean
  /**
   * Whether the reader's machine has this plugin, or null when unknown.
   *
   * This page is served by an edge function and cannot see anyone's machine, so the state is told
   * to it by the Toolbox, which is what links here. Null is a THIRD state, not a synonym for "not
   * installed": somebody arriving from a shared link must not be invited to install what they may
   * already have.
   */
  installed?: boolean | null
  banner?: { kind: "ok" | "error"; message: string } | null
}

/** What each visibility value means, in the reader's terms rather than the column's. */
const VISIBILITY_COPY: Record<string, { label: string; detail: string }> = {
  public: {
    label: "Public",
    detail: "Anyone can find and install it, including people outside this organisation.",
  },
  org: {
    label: "Organisation only",
    detail: "Only members of this organisation can find and install it.",
  },
  unlisted: {
    label: "Unlisted",
    // Administrators, and its author. `user_can_view_plugin_row` answers the author arm before it
    // reaches the unlisted one, so saying "administrators" alone would be a claim the predicate
    // does not make.
    detail: "Hidden from the store. Only this organisation's administrators and the plugin's author can see it.",
  },
}

export function pluginPage(options: PluginPageOptions): string {
  const { nonce, basePath, orgSlug, csrf, plugin, readme, canEdit, banner } = options
  const action = `${basePath}/o/${encodeURIComponent(orgSlug)}/plugins/${
    encodeURIComponent(plugin.plugin_id)
  }/visibility`

  return layout({
    title: `${plugin.display_name} - BOSS`,
    nonce,
    banner: banner ?? null,
    body: `
<header class="page">
  <h1>${esc(plugin.display_name)}</h1>
  <span class="slug">@${esc(orgSlug)}</span>
</header>
<p class="sub">${esc(plugin.plugin_id)}</p>

${identityCard(plugin)}
${actionCard(plugin, options.installed ?? null)}
${canEdit ? visibilityCard(action, csrf, plugin) : visibilityReadOnly(plugin)}
${readmeCard(plugin, readme)}`,
  })
}

/**
 * Icon, description and the facts worth knowing before installing.
 *
 * The icon is rendered from `icon_url`, which is publisher-supplied. `img-src` in the CSP is
 * `'self' data:` - it does NOT include remote hosts - so a remote icon simply does not load and
 * the alt text stands in. That is the intended outcome rather than a gap: allowing arbitrary
 * remote images here would let a publisher log every reader of their plugin's page by URL.
 */
function identityCard(plugin: PluginDetail): string {
  const facts: Array<[string, string]> = []
  if (plugin.latest_version) facts.push(["Latest version", plugin.latest_version])
  if (plugin.type) facts.push(["Type", plugin.type])
  if (plugin.api_version) facts.push(["Plugin API", plugin.api_version])
  if (plugin.author_name) facts.push(["Published by", plugin.author_name])
  if (typeof plugin.download_count === "number") {
    facts.push(["Installs", String(plugin.download_count)])
  }

  return `
<section class="card">
  <div class="row">
    <div>
      ${
    plugin.icon_url
      // attrUrl for the same reason, even though img-src is `'self' data:` and a remote icon
      // cannot load: the CSP is what stops it being FETCHED, not what stops a publisher-supplied
      // string reaching an attribute. Belt and braces, matching every other URL on these pages.
      ? `<img src="${attrUrl(plugin.icon_url, ["http", "https", "data"])}" alt="${
        esc(plugin.display_name)
      } icon" width="48" height="48">`
      : ""
  }
      <h2>${esc(plugin.display_name)}</h2>
      ${
    plugin.description
      ? `<p class="hint">${esc(plugin.description)}</p>`
      : '<p class="hint">No description.</p>'
  }
      ${
    plugin.verified ? '<span class="pill ok">verified</span>' : ""
  }${plugin.published ? "" : ' <span class="pill warn">unpublished</span>'}
    </div>
  </div>
  ${
    facts.length === 0 ? "" : scrollable(
      "Plugin details",
      `<table>
    <tbody>${
        facts.map(([k, v]) => `<tr><td>${esc(k)}</td><td class="mono">${esc(v)}</td></tr>`).join("")
      }</tbody>
  </table>`,
    )
  }
  ${
    plugin.homepage_url
      // A LINK, and through attrUrl with http/https opted in rather than esc alone. homepage_url
      // is publisher-supplied, so this is the one field on the page where a `javascript:` could
      // reach an href; attrUrl collapses anything but http and https to "#". Same treatment the
      // org page gives its website field, and the same reason.
      ? `<p class="hint">Source: <a class="mono" href="${
        attrUrl(plugin.homepage_url, ["http", "https"])
      }" target="_blank" rel="noopener noreferrer nofollow">${esc(plugin.homepage_url)}</a></p>`
      : ""
  }
</section>`
}

/**
 * Open it, or install it.
 *
 * Both are `boss://` deep links, so the desktop app does the work and this page never has to know
 * anything about the reader's machine. The LABEL follows the hint the Toolbox passed; the ACTION
 * behind it carries the plugin id and lets the app decide, so a stale or absent hint costs a wrong
 * word and never a wrong outcome - asking to open something that is not installed offers to
 * install it, and asking to install something already present says so.
 *
 * Nothing is installed by pressing this. The link reaches the Toolbox's handler, which shows a
 * prompt naming the plugin as the STORE describes it and installs on that press. Worth stating
 * here because this page is public: anyone can serve a link to it.
 *
 * `attrUrl` with `boss` opted in. It is a scheme this file constructs from a value that came out
 * of the database, and the same rule applies to it as to every other URL on the page.
 */
function actionCard(plugin: PluginDetail, installed: boolean | null): string {
  const toolbox = "ai.rever.boss.plugin.dynamic.pluginmanager"
  const link = (action: string) =>
    `boss://plugin?id=${toolbox}&action=${action}&plugin=${encodeURIComponent(plugin.plugin_id)}`

  // Unpublished means there is nothing to fetch, so neither button would do anything.
  if (!plugin.published) {
    return `
<section class="card">
  <h2>Get it</h2>
  <p class="hint">This plugin is not published, so it cannot be installed from the store yet.</p>
</section>`
  }

  const action = installed === true ? "open" : "install"
  const label = installed === true ? "Open in BOSS" : "Install in BOSS"
  const note = installed === true
    ? "Opens it in the BOSS desktop app."
    : installed === false
    ? "Opens BOSS and asks before installing. Nothing is installed until you confirm."
    : // The honest wording for a reader we know nothing about.
      "Opens BOSS. If you already have it, BOSS says so instead of installing it again."

  return `
<section class="card">
  <h2>Get it</h2>
  <p>
    <a class="button" href="${attrUrl(link(action), ["boss"])}">${esc(label)}</a>
  </p>
  <p class="hint">${esc(note)}</p>
</section>`
}

/**
 * The visibility control, for an administrator of the owning organisation.
 *
 * Radios rather than a select, because there are exactly three and each needs a sentence: the
 * consequence of `org` and `unlisted` is not guessable from the word. It is a POST form with a
 * CSRF field like every other mutation here.
 *
 * The warning under it is the thing most worth saying: the Toolbox reads its catalogue as `anon`,
 * so anything other than `public` disappears from the store listing for EVERY reader, including
 * members of the owning organisation. That is a property of how the client reads today, not of
 * what the visibility means, and somebody flipping this switch should know it before they do.
 */
function visibilityCard(action: string, csrf: string, plugin: PluginDetail): string {
  const options = Object.entries(VISIBILITY_COPY).map(([value, copy]) => `
    <div class="checkline">
      <input type="radio" id="vis_${esc(value)}" name="visibility" value="${esc(value)}"${
    plugin.visibility === value ? " checked" : ""
  }>
      <label for="vis_${esc(value)}"><strong>${esc(copy.label)}</strong> - ${esc(copy.detail)}</label>
    </div>`).join("")

  return `
<section class="card">
  <h2>Visibility</h2>
  <p class="hint">Who can find and install this plugin.</p>
  <form method="post" action="${esc(action)}">
    ${csrfField(CSRF_FIELD, csrf)}
    ${options}
    <button type="submit">Save visibility</button>
  </form>
  <p class="hint">Restricting a plugin removes it from the public store listing. Members of this organisation still find it in their Toolbox, because the Toolbox now reads the store as the signed-in user. Anyone on an older Toolbox reads it anonymously and will not see it at all.</p>
</section>`
}

/** The same fact, for somebody who may not change it. */
function visibilityReadOnly(plugin: PluginDetail): string {
  const copy = VISIBILITY_COPY[plugin.visibility] ??
    { label: plugin.visibility, detail: "" }
  return `
<section class="card">
  <h2>Visibility</h2>
  <p class="hint"><strong>${esc(copy.label)}</strong>${
    copy.detail ? ` - ${esc(copy.detail)}` : ""
  }</p>
  <p class="hint">Only this organisation's administrators can change it.</p>
</section>`
}

/**
 * The README, rendered.
 *
 * Rendered by services/markdown.ts, which escapes the source BEFORE applying any formatting, so
 * every tag below is one we wrote and nothing the README supplies can become markup. That is why
 * there is no library involved: a general Markdown renderer passes raw HTML through by design,
 * which would make the CSP the only thing between somebody else's repository and the admin control
 * on this page.
 */
function readmeCard(plugin: PluginDetail, readme: string | null): string {
  if (!readme) {
    return `
<section class="card">
  <h2>About</h2>
  <p class="hint">${
      plugin.homepage_url
        ? "No README could be read from this plugin's repository."
        : "This plugin has no repository to read a README from."
    }</p>
</section>`
  }

  return `
<section class="card">
  <h2>About</h2>
  <p class="hint">From the plugin's README.</p>
  <div class="md">${renderMarkdown(readme)}</div>
</section>`
}
