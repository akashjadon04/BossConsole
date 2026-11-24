/**
 * The plugin page.
 *
 * Two of these are the whole reason the page is written the way it is, and neither is visible in
 * the rendered output: a plugin belonging to ANOTHER organisation must not render or be writable
 * through this org's session, and the README must never reach the page as markup.
 */

import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import { githubRepoFromUrl } from "../services/readme.ts"
import { pluginPage } from "../views/plugin.ts"
import type { PluginDetail } from "../services/plugin.ts"

const NONCE = "nonce"

function plugin(overrides: Partial<PluginDetail> = {}): PluginDetail {
  return {
    id: "11111111-1111-4111-8111-111111111111",
    plugin_id: "ai.rever.boss.plugin.dynamic.codexglm",
    display_name: "Codex GLM",
    description: "An AI provider",
    author_name: "Risa Labs",
    homepage_url: "https://github.com/risa-labs-inc/boss-plugin-codexglm",
    icon_url: null,
    type: "panel",
    api_version: "1.0.73",
    verified: false,
    published: true,
    visibility: "public",
    org_id: "22222222-2222-4222-8222-222222222222",
    org_slug: "risa",
    download_count: 12,
    latest_version: "1.0.4",
    updated_at: "2026-08-01T00:00:00Z",
    ...overrides,
  }
}

function render(
  opts: {
    readme?: string | null
    canEdit?: boolean
    p?: Partial<PluginDetail>
    installed?: boolean | null
  } = {},
) {
  return pluginPage({
    nonce: NONCE,
    basePath: "/functions/v1/organisation",
    orgSlug: "risa",
    csrf: "csrf-token",
    plugin: plugin(opts.p),
    readme: opts.readme ?? null,
    canEdit: opts.canEdit ?? false,
    installed: opts.installed ?? null,
  })
}

// ---------------------------------------------------------------------------
// The README is text, never markup
// ---------------------------------------------------------------------------

Deno.test("a README is escaped, not rendered as markup", () => {
  // Somebody else's repository content, on a page that also carries an admin form. Rendering it
  // as HTML would make the CSP the only thing between a README and that form, and a CSP is the
  // second line of defence.
  const html = render({ readme: "# Title\n\n<img src=x onerror=alert(1)>\n<script>alert(2)</script>" })
  assertEquals(/<img[^>]*onerror/.test(html), false)
  assertEquals(html.includes("<script>alert(2)</script>"), false)
  // Present, but as text. Rendering the markdown around it changes nothing about this: the source
  // is escaped BEFORE any formatting rule runs, so no rule can hand a tag back.
  assertStringIncludes(html, "&lt;img src=x onerror=alert(1)&gt;")
  assertStringIncludes(html, "&lt;script&gt;")
})

Deno.test("markdown is rendered, with headings placed under the page's own", () => {
  // The page owns h1 and h2. A README that started at h1 would claim the document outline from
  // the plugin whose page this is.
  const html = render({ readme: "# Title\n\nSome **bold** prose." })
  assertStringIncludes(html, "<h3>Title</h3>")
  assertStringIncludes(html, "<strong>bold</strong>")
  assertEquals(html.includes("<h1>Title</h1>"), false)
})

Deno.test("no README says so rather than rendering an empty block", () => {
  const withRepo = render({ readme: null })
  assertStringIncludes(withRepo, "No README could be read")
  const withoutRepo = render({ readme: null, p: { homepage_url: null } })
  assertStringIncludes(withoutRepo, "no repository")
})

// ---------------------------------------------------------------------------
// The visibility control
// ---------------------------------------------------------------------------

Deno.test("an admin gets a form; a member gets the value only", () => {
  const admin = render({ canEdit: true })
  assertStringIncludes(admin, "/plugins/ai.rever.boss.plugin.dynamic.codexglm/visibility")
  assertStringIncludes(admin, 'name="visibility"')
  assertStringIncludes(admin, "csrf-token")

  const member = render({ canEdit: false })
  assertEquals(member.includes('name="visibility"'), false)
  assertEquals(member.includes("<form"), false)
  // Still told what it is, and who may change it.
  assertStringIncludes(member, "Public")
  assertStringIncludes(member, "administrators can change it")
})

Deno.test("the current value is the one preselected", () => {
  const html = render({ canEdit: true, p: { visibility: "org" } })
  assertStringIncludes(html, 'value="org" checked')
  assertEquals(html.includes('value="public" checked'), false)
})

Deno.test("an unexpected visibility selects nothing rather than everything", () => {
  // The column is NOT NULL with a CHECK, so this should be unreachable - but a control that
  // silently preselected a value the row does not have would save the wrong thing on the next
  // press.
  const html = render({ canEdit: true, p: { visibility: "something-else" } })
  // Matched against an INPUT, not the whole document: `includes("checked")` also matches the
  // word inside a CSS comment in the inlined stylesheet, which is how the first version of this
  // test failed against correct code.
  assertEquals(/<input[^>]*checked/.test(html), false)
})

Deno.test("the consequence of restricting visibility is stated on the page", () => {
  // What restricting COSTS is a property of how clients read, not of what the value means, so the
  // page has to say it - and it changed: the Toolbox used to read the catalogue as `anon`, which
  // hid an org plugin from the very members it exists for. It reads as the signed-in user now, so
  // the page must no longer claim the plugin vanishes "for everyone".
  const html = render({ canEdit: true })
  assertStringIncludes(html, "removes it from the public store listing")
  assertStringIncludes(html, "Members of this organisation still find it")
  // The stale claim, asserted absent so it cannot come back by a copy-paste.
  assertEquals(html.includes("for everyone"), false)
  // The caveat that is still true: an older Toolbox reads anonymously.
  assertStringIncludes(html, "older Toolbox")
})

// ---------------------------------------------------------------------------
// The README fetcher's URL rule
// ---------------------------------------------------------------------------

Deno.test("only github.com repositories are addressable", () => {
  assertEquals(githubRepoFromUrl("https://github.com/owner/repo"), { owner: "owner", repo: "repo" })
  assertEquals(
    githubRepoFromUrl("https://github.com/owner/repo/tree/main/sub"),
    { owner: "owner", repo: "repo" },
  )
  assertEquals(githubRepoFromUrl("https://github.com/owner/repo.git"), { owner: "owner", repo: "repo" })
})

Deno.test("a lookalike host is refused", () => {
  // The check is an exact hostname match, never includes("github.com"). homepage_url is
  // publisher-supplied, so without that this page is a server-side fetcher pointed by whoever
  // published the plugin.
  assertEquals(githubRepoFromUrl("https://github.com.evil.test/owner/repo"), null)
  assertEquals(githubRepoFromUrl("https://notgithub.com/owner/repo"), null)
  assertEquals(githubRepoFromUrl("https://evil.test/?x=github.com/owner/repo"), null)
})

Deno.test("non-http schemes and malformed URLs are refused", () => {
  assertEquals(githubRepoFromUrl("file:///etc/passwd"), null)
  assertEquals(githubRepoFromUrl("javascript:alert(1)"), null)
  assertEquals(githubRepoFromUrl("not a url"), null)
  assertEquals(githubRepoFromUrl(null), null)
  assertEquals(githubRepoFromUrl(""), null)
})

Deno.test("nothing that reaches the request path can contain a separator", () => {
  // The property is the CHARSET, not a traversal check. `https://github.com/../../etc/repo` is
  // normalised by the URL parser to `/etc/repo` before this function sees it - a perfectly
  // ordinary path - so there is no traversal left to refuse. What matters is that whatever
  // survives is interpolated into api.github.com/repos/<owner>/<repo> and therefore cannot carry
  // a slash, a dot-segment or a query.
  const normalised = githubRepoFromUrl("https://github.com/../../etc/repo")
  assertEquals(normalised, { owner: "etc", repo: "repo" })

  for (
    const url of [
      "https://github.com/own er/repo",
      "https://github.com/owner%2F../repo",
      "https://github.com/ow$ner/repo",
    ]
  ) {
    const parsed = githubRepoFromUrl(url)
    if (parsed !== null) {
      assert(/^[A-Za-z0-9._-]+$/.test(parsed.owner), `owner escaped the charset: ${parsed.owner}`)
      assert(/^[A-Za-z0-9._-]+$/.test(parsed.repo), `repo escaped the charset: ${parsed.repo}`)
    }
  }
})

Deno.test("a repository URL with no repo segment is refused", () => {
  assertEquals(githubRepoFromUrl("https://github.com/owner"), null)
  assertEquals(githubRepoFromUrl("https://github.com/"), null)
})

// ---------------------------------------------------------------------------
// The section that links to this page
// ---------------------------------------------------------------------------
// Written because the page above was unreachable for its whole first draft: it rendered, it was
// tested, and nothing on any other page pointed at it. A route with no way in is not a feature.

import { orgPage } from "../views/org.ts"
import type { OrgPluginSummary } from "../services/plugin.ts"
import type { OrgDetail } from "../services/org.ts"

function summary(overrides: Partial<OrgPluginSummary> = {}): OrgPluginSummary {
  return {
    plugin_id: "ai.rever.boss.plugin.dynamic.codexglm",
    display_name: "Codex GLM",
    description: "An AI provider",
    icon_url: null,
    visibility: "public",
    published: true,
    verified: false,
    ...overrides,
  }
}

function orgDetail(overrides: Partial<OrgDetail> = {}): OrgDetail {
  return {
    id: "22222222-2222-4222-8222-222222222222",
    slug: "risa",
    name: "RISA Labs",
    description: null,
    website: null,
    visibility: "private",
    join_policy: "invite_only",
    publish_policy: "admins",
    member_count: 3,
    is_admin: true,
    is_system: false,
    owner_email: "shivang@risalabs.ai",
    primary_domain: null,
    ...overrides,
  } as OrgDetail
}

function overview(plugins: OrgPluginSummary[], org: Partial<OrgDetail> = {}) {
  return orgPage({
    nonce: NONCE,
    basePath: "/functions/v1/organisation",
    org: orgDetail(org),
    members: [],
    roles: [],
    plugins,
  })
}

Deno.test("each plugin links to its page under the owning organisation", () => {
  const html = overview([summary()])
  assertStringIncludes(
    html,
    'href="/functions/v1/organisation/o/risa/plugins/ai.rever.boss.plugin.dynamic.codexglm"',
  )
  assertStringIncludes(html, "Codex GLM")
})

Deno.test("a plugin id is encoded into the href, not pasted in", () => {
  // Nothing constrains plugins.plugin_id to the dotted reverse-DNS shape every row happens to
  // have, and this value is built into an href. A `?` or `#` in it would silently truncate the
  // path and send the reader to a different plugin's page, or to none.
  const html = overview([summary({ plugin_id: "a b?c#d" })])
  assertStringIncludes(html, "/plugins/a%20b%3Fc%23d")
  assertEquals(html.includes("/plugins/a b?c#d"), false)
})

Deno.test("a plugin name cannot inject markup into the list", () => {
  const html = overview([summary({ display_name: "<script>alert(1)</script>" })])
  assertEquals(html.includes("<script>alert(1)</script>"), false)
  assertStringIncludes(html, "&lt;script&gt;")
})

Deno.test("a restricted visibility is distinguishable at a glance", () => {
  // The reason the column exists: `org` and `unlisted` are the states somebody scanning the list
  // needs to spot, and they are also the states that remove a plugin from the Toolbox.
  const html = overview([summary({ visibility: "org" }), summary({ plugin_id: "b", visibility: "public" })])
  assertStringIncludes(html, '<span class="pill admin">org</span>')
  assertStringIncludes(html, '<span class="pill">public</span>')
})

Deno.test("an organisation with no plugins says so", () => {
  const html = overview([])
  assertStringIncludes(html, "No plugins published under this organisation")
  assertEquals(html.includes("<th>Visibility</th>"), false)
})

Deno.test("a member is not invited to change anything", () => {
  // The hint offers the visibility control, which a non-admin does not get. Promising it to
  // somebody who then cannot use it is worse than saying less.
  assertStringIncludes(overview([summary()], { is_admin: true }), "set who can see it")
  assertEquals(overview([summary()], { is_admin: false }).includes("set who can see it"), false)
})

// ---------------------------------------------------------------------------
// The shape the RPC actually returns
// ---------------------------------------------------------------------------
// Written after review found the page 404'd for everybody while every test above passed. Nothing
// here rendered the page from a real read: the tests supplied a PluginDetail fixture directly, so
// the one step between the database and the view was the only thing never exercised.
//
// get_plugin_with_stats_for_viewer is declared RETURNS TABLE, so PostgREST answers with a bare
// array of rows. callRpc fails closed on anything without `success: true` - correct for the org
// RPCs, all of which return a scalar jsonb envelope, and fatal for this one.

import { loadPlugin } from "../services/plugin.ts"
import { setServiceClientForTests } from "../utils/org-rpc.ts"

/** A client that answers one rpc() call with whatever PostgREST would have said. */
// deno-lint-ignore no-explicit-any
function clientReturning(data: unknown, error: unknown = null): any {
  return { rpc: (_fn: string, _params: unknown) => Promise.resolve({ data, error }) }
}

const ROW = {
  id: "11111111-1111-4111-8111-111111111111",
  plugin_id: "probe.plugin",
  display_name: "Probe",
  description: null,
  author_name: "P",
  homepage_url: null,
  icon_url: null,
  type: "panel",
  api_version: "1.0",
  verified: false,
  published: true,
  visibility: "public",
  org_id: null,
  org_slug: null,
  download_count: 0,
  latest_version: "1.0.0",
  updated_at: null,
}

async function withClient(data: unknown, error: unknown, body: () => Promise<void>) {
  setServiceClientForTests(clientReturning(data, error))
  try {
    await body()
  } finally {
    setServiceClientForTests(null)
  }
}

Deno.test("a plugin is loaded from the ARRAY a set-returning RPC returns", async () => {
  await withClient([ROW], null, async () => {
    const plugin = await loadPlugin("probe.plugin", "aaaaaaaa-0000-4000-8000-00000000000a")
    assert(plugin !== null, "a visible plugin came back as null - the page would 404 for everyone")
    assertEquals(plugin.display_name, "Probe")
    assertEquals(plugin.visibility, "public")
  })
})

Deno.test("no rows is null, which is the not-found and the not-visible case alike", async () => {
  await withClient([], null, async () => {
    assertEquals(await loadPlugin("probe.plugin", "aaaaaaaa-0000-4000-8000-00000000000a"), null)
  })
})

Deno.test("a database error is null rather than a thrown page", async () => {
  await withClient(null, { message: "boom" }, async () => {
    assertEquals(await loadPlugin("probe.plugin", "aaaaaaaa-0000-4000-8000-00000000000a"), null)
  })
})

Deno.test("an envelope where rows were expected is refused, not read as a row", async () => {
  // The other direction of the same mistake: if this function is ever changed to return the org
  // envelope, reading `{success: true}` as row zero would produce a plugin with no id.
  await withClient({ success: true, data: [] }, null, async () => {
    assertEquals(await loadPlugin("probe.plugin", "aaaaaaaa-0000-4000-8000-00000000000a"), null)
  })
})

// ---------------------------------------------------------------------------
// The README cache
// ---------------------------------------------------------------------------
// The page it feeds is readable without a session, so every render was one outbound GitHub call
// that a stranger could trigger in a loop. GITHUB_TOKEN is shared with plugin-store's github
// service, so exhausting it takes that down too.

import { clearReadmeCacheForTests, fetchReadme } from "../services/readme.ts"

const REPO = "https://github.com/risa-labs-inc/boss-plugin-codexglm"

/** Counts outbound calls and answers with whatever GitHub is being pretended to say. */
function countingFetch(response: () => Response) {
  let calls = 0
  const original = globalThis.fetch
  globalThis.fetch = (() => {
    calls += 1
    return Promise.resolve(response())
  }) as typeof fetch
  return { count: () => calls, restore: () => { globalThis.fetch = original } }
}

Deno.test("a second read of the same repository does not call GitHub again", async () => {
  clearReadmeCacheForTests()
  const f = countingFetch(() => new Response("# Codex", { status: 200 }))
  try {
    assertEquals(await fetchReadme(REPO), "# Codex")
    assertEquals(await fetchReadme(REPO), "# Codex")
    assertEquals(await fetchReadme(REPO), "# Codex")
    assertEquals(f.count(), 1, "the README was re-fetched despite the cache")
  } finally {
    f.restore()
    clearReadmeCacheForTests()
  }
})

Deno.test("a failure is cached too, which is the case an attacker would pick", async () => {
  // Caching only successes would leave a private or missing repository re-asked every request.
  clearReadmeCacheForTests()
  const f = countingFetch(() => new Response("nope", { status: 404 }))
  try {
    assertEquals(await fetchReadme(REPO), null)
    assertEquals(await fetchReadme(REPO), null)
    assertEquals(f.count(), 1, "a 404 was re-fetched, so the cheap path stays uncached")
  } finally {
    f.restore()
    clearReadmeCacheForTests()
  }
})

Deno.test("different repositories are cached separately", async () => {
  clearReadmeCacheForTests()
  let body = "first"
  const f = countingFetch(() => new Response(body, { status: 200 }))
  try {
    assertEquals(await fetchReadme("https://github.com/a/one"), "first")
    body = "second"
    assertEquals(await fetchReadme("https://github.com/a/two"), "second")
    assertEquals(await fetchReadme("https://github.com/a/one"), "first")
    assertEquals(f.count(), 2)
  } finally {
    f.restore()
    clearReadmeCacheForTests()
  }
})

Deno.test("a URL that is not a repository never reaches the network or the cache", async () => {
  clearReadmeCacheForTests()
  const f = countingFetch(() => new Response("x", { status: 200 }))
  try {
    assertEquals(await fetchReadme("https://github.com.evil.test/a/b"), null)
    assertEquals(await fetchReadme(null), null)
    assertEquals(f.count(), 0)
  } finally {
    f.restore()
    clearReadmeCacheForTests()
  }
})

// ---------------------------------------------------------------------------
// The source link
// ---------------------------------------------------------------------------

Deno.test("the source repository is a link, not text to copy out by hand", () => {
  const html = render()
  assertStringIncludes(
    html,
    '<a class="mono" href="https://github.com/risa-labs-inc/boss-plugin-codexglm"',
  )
  assertStringIncludes(html, 'rel="noopener noreferrer nofollow"')
})

Deno.test("a javascript: homepage cannot reach the href", () => {
  // homepage_url is publisher-supplied and is the one field on this page that reaches an href.
  // attrUrl collapses anything but http and https to "#"; esc alone would have let this through
  // as a working link, because there is nothing to escape in it.
  const html = render({ p: { homepage_url: "javascript:alert(1)" } })
  assertEquals(html.includes('href="javascript:'), false)
  assertStringIncludes(html, 'href="#"')
})

Deno.test("a protocol-relative homepage is refused", () => {
  const html = render({ p: { homepage_url: "//evil.test/x" } })
  assertEquals(html.includes('href="//evil.test'), false)
})

Deno.test("a publisher-supplied icon url goes through the same gate", () => {
  // The CSP stops a remote icon being FETCHED. It does not stop the string reaching the attribute,
  // which is a different job.
  const html = render({ p: { icon_url: "javascript:alert(1)" } })
  assertEquals(html.includes('src="javascript:'), false)
})

// ---------------------------------------------------------------------------
// Open or Install
// ---------------------------------------------------------------------------
// The page cannot see the reader's machine, so the Toolbox tells it. The LABEL follows that hint;
// the deep link behind it carries the plugin id and lets the app decide, which is why a stale or
// absent hint is a wording problem and never a correctness one.

function actionOf(html: string): { label: string; href: string } | null {
  const m = /<a class="button" href="([^"]+)">([^<]+)<\/a>/.exec(html)
  return m ? { href: m[1], label: m[2] } : null
}

Deno.test("an installed plugin offers Open", () => {
  const a = actionOf(render({ p: {} , installed: true }))!
  assertEquals(a.label, "Open in BOSS")
  assertStringIncludes(a.href, "action=open")
  assertStringIncludes(a.href, "plugin=ai.rever.boss.plugin.dynamic.codexglm")
})

Deno.test("a plugin that is not installed offers Install", () => {
  const a = actionOf(render({ installed: false }))!
  assertEquals(a.label, "Install in BOSS")
  assertStringIncludes(a.href, "action=install")
})

Deno.test("an unknown state does not tell the reader to install what they may have", () => {
  // The third state. Somebody arriving from a shared link is told what BOSS will do rather than
  // being invited to install something they might already be running.
  const html = render()
  assertStringIncludes(html, "If you already have it, BOSS says so")

  // And it takes the install route, whose handler answers "already installed" for somebody who
  // has it. Asserted because the label and the action are set on separate lines: they agreed by
  // accident until a mutation flipped one of them and no test noticed.
  const a = actionOf(html)!
  assertEquals(a.label, "Install in BOSS")
  assertStringIncludes(a.href, "action=install")
})

Deno.test("the link addresses the Toolbox's handler, not the plugin's own panel", () => {
  // boss://plugin?id= takes the id of whoever HANDLES the action. Addressing the target plugin
  // would only work for plugins whose panel id happens to equal their plugin id.
  const a = actionOf(render({ installed: false }))!
  assertStringIncludes(a.href, "boss://plugin?id=ai.rever.boss.plugin.dynamic.pluginmanager")
})

Deno.test("an unpublished plugin offers neither", () => {
  const html = render({ p: { published: false } })
  assertEquals(actionOf(html), null)
  assertStringIncludes(html, "cannot be installed from the store yet")
})

Deno.test("a plugin id with a separator cannot break out of the deep link", () => {
  const html = render({ installed: false, p: { plugin_id: "a&b=c" } })
  const a = actionOf(html)!
  assertStringIncludes(a.href, "plugin=a%26b%3Dc")
  assertEquals(a.href.includes("plugin=a&b=c"), false)
})
