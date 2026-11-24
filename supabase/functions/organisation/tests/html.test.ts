/**
 * Output escaping. Each context gets its own assertions, because "we escape
 * things" is not a property - escaping for the wrong context is the bug.
 */

import { assert, assertEquals } from "@std/assert"
import { attrUrl, cspNonce, esc, jsonForScript, scrollable } from "../utils/html.ts"
import { layout } from "../views/layout.ts"

Deno.test("esc neutralises every HTML metacharacter", () => {
  assertEquals(esc(`<script>`), "&lt;script&gt;")
  assertEquals(esc(`&`), "&amp;")
  assertEquals(esc(`"`), "&quot;")
  assertEquals(esc(`'`), "&#39;")
  assertEquals(esc(`<img src=x onerror="alert(1)">`), "&lt;img src=x onerror=&quot;alert(1)&quot;&gt;")
})

Deno.test("esc escapes & first, so entities are not double-decoded", () => {
  // If & were escaped last, "&lt;" would come out as "&lt;" and render as "<".
  assertEquals(esc("&lt;script&gt;"), "&amp;lt;script&amp;gt;")
})

Deno.test("esc renders null and undefined as empty, not as the word", () => {
  assertEquals(esc(null), "")
  assertEquals(esc(undefined), "")
  assertEquals(esc(0), "0")
  assertEquals(esc(false), "false")
})

Deno.test("jsonForScript cannot break out of a script block", () => {
  const out = jsonForScript({ name: "</script><script>alert(1)</script>" })
  // Escaping `<` alone is what matters: the HTML parser cannot see a tag,
  // opening or closing, without it. `>` is left as-is deliberately.
  assertEquals(out.includes("</script>"), false)
  assertEquals(out.includes("<"), false)
  assert(out.includes("\\u003c/script>"))
})

Deno.test("jsonForScript escapes the U+2028 and U+2029 line separators", () => {
  // Legal in JSON, but raw line terminators inside a JS string literal. This is
  // the case redirect/app.ts documents skipping because its inputs are
  // pre-encoded; ours are not.
  const out = jsonForScript({ name: `a\u2028b\u2029c` })
  assertEquals(out.includes("\u2028"), false)
  assertEquals(out.includes("\u2029"), false)
  assert(out.includes("\\u2028"))
  assert(out.includes("\\u2029"))
})

Deno.test("jsonForScript output is still valid JSON", () => {
  const value = { name: "</script>", sep: "a\u2028b" }
  const parsed = JSON.parse(jsonForScript(value))
  assertEquals(parsed.name, "</script>")
  assertEquals(parsed.sep, "a\u2028b")
})

Deno.test("attrUrl allows same-origin absolute paths", () => {
  assertEquals(attrUrl("/functions/v1/organisation/o/acme"), "/functions/v1/organisation/o/acme")
})

Deno.test("attrUrl refuses script-bearing and cross-origin schemes", () => {
  for (
    const bad of [
      "javascript:alert(1)",
      "JavaScript:alert(1)",
      " javascript:alert(1)",
      "data:text/html,<script>alert(1)</script>",
      "vbscript:msgbox",
      "//evil.example.com/path",
      "/\\evil.example.com/path",
      "\\\\evil.example.com/path",
      "\\/evil.example.com/path",
      "https://evil.example.com",
      "http://evil.example.com",
      "relative/path",
      "",
    ]
  ) {
    assertEquals(attrUrl(bad), "#", `should refuse: ${bad}`)
  }
})

Deno.test("attrUrl allows a deep-link scheme only when it is opted in", () => {
  assertEquals(attrUrl("boss://organisation/join?token=x"), "#")
  assertEquals(
    attrUrl("boss://organisation/join?token=x", ["boss"]),
    "boss://organisation/join?token=x",
  )
  // Opting in to `boss` does not open the door to anything else.
  assertEquals(attrUrl("javascript:alert(1)", ["boss"]), "#")
})

Deno.test("attrUrl escapes what it lets through", () => {
  assertEquals(attrUrl(`/o/acme"><script>`), "/o/acme&quot;&gt;&lt;script&gt;")
})

Deno.test("cspNonce is unique and base64url", () => {
  const seen = new Set<string>()
  for (let i = 0; i < 200; i++) {
    const nonce = cspNonce()
    assert(/^[A-Za-z0-9_-]+$/.test(nonce), `not base64url: ${nonce}`)
    seen.add(nonce)
  }
  assertEquals(seen.size, 200)
})

Deno.test("no view emits a duplicate class attribute", async () => {
  // A parser keeps the FIRST class attribute and silently discards the rest, so
  // `class="card" class="highlight"` drops the second - and these utility classes exist only
  // because the CSP forbids inline style, meaning the styling vanishes with no error anywhere.
  //
  // This has now happened three times: the invite card, then two in join.ts that the previous
  // guard missed because it only inspected the rendered ADMIN page. Scanning the source of every
  // view catches them wherever they are written.
  const dir = new URL("../views/", import.meta.url)
  for await (const entry of Deno.readDir(dir)) {
    if (!entry.name.endsWith(".ts")) continue
    const source = await Deno.readTextFile(new URL(entry.name, dir))
    const dupes = source.match(/class="[^"]*"[^>]*class="[^"]*"/g) ?? []
    assertEquals(dupes, [], `${entry.name} has duplicate class attributes: ${dupes.join(", ")}`)
  }
})

Deno.test("every page is inside Cloudflare's email-obfuscation opt-out", () => {
  // api.risaboss.com is behind Cloudflare, which rewrites any address in an HTML
  // response into a __cf_email__ anchor needing a decoder script our CSP blocks.
  // Every member on the roster rendered as the literal words "email protected".
  //
  // Asserted on the LAYOUT, not on each field. The first fix wrapped the four
  // known email columns, and the failure is per-response: a join request reading
  // "reach me at me@corp.com" goes through request_message and broke identically.
  // One region covers the class; a list of fields covers whatever was remembered.
  const page = layout({ title: "t", nonce: "n", body: "<p>someone@example.com</p>" })

  const open = page.indexOf("<!--email_off-->")
  const close = page.indexOf("<!--/email_off-->")
  const content = page.indexOf("someone@example.com")

  assert(open > -1, "no email_off region")
  assert(close > open, "the region is not closed after it opens")
  assert(content > open && content < close, "page content is outside the region")
})

Deno.test("free text that is not an email column is inside the region too", () => {
  // The case the per-field fix missed. request_message is whatever a person typed.
  const page = layout({
    title: "t",
    nonce: "n",
    body: `<td>${esc("reach me at me@corp.com")}</td>`,
  })
  const open = page.indexOf("<!--email_off-->")
  const close = page.indexOf("<!--/email_off-->")
  const content = page.indexOf("me@corp.com")
  assert(content > open && content < close)
})

Deno.test("the region wraps the page exactly once", () => {
  // Nesting regions is not something Cloudflare documents, and two opens would
  // mean someone reintroduced the per-field form inside the per-page one.
  const page = layout({ title: "t", nonce: "n", body: "<p>x</p>" })
  assertEquals(page.split("<!--email_off-->").length - 1, 1)
  assertEquals(page.split("<!--/email_off-->").length - 1, 1)
})

Deno.test("every table sits in a focusable scroll region", async () => {
  // The scroll container replaced a rule that hid every column past the third.
  // But a scrollable region is only operable from a keyboard if it can take focus
  // (WCAG 2.1.1), and these tables are read-only - no links, no controls, nothing
  // focusable inside. Without tabindex the columns are reachable with a mouse and
  // unreachable otherwise, which is the same content loss in a different modality.
  //
  // Source-scanned: whether a table is wrapped is a property of the markup, and
  // the failure is invisible in rendered output unless you try to tab to it.
  const dir = new URL("../views/", import.meta.url)
  for await (const entry of Deno.readDir(dir)) {
    if (!entry.name.endsWith(".ts")) continue
    const source = await Deno.readTextFile(new URL(entry.name, dir))
    // <table[\s>] rather than <table>, so adding an attribute cannot silently
    // drop a table out of the count.
    const tables = (source.match(/<table[\s>]/g) ?? []).length
    if (tables === 0) continue
    const wrappers = (source.match(/class="scroller"/g) ?? []).length +
      (source.match(/scrollable\(/g) ?? []).length
    assertEquals(
      wrappers,
      tables,
      `${entry.name}: ${tables} table(s) but ${wrappers} scroll wrapper(s)`,
    )
  }
})

Deno.test("the scroll region is focusable and named", () => {
  // A focusable div with no accessible name announces as nothing, so the tab stop
  // becomes a mystery rather than a feature.
  const out = scrollable("Members", "<table></table>")
  assert(out.includes('tabindex="0"'), "not focusable")
  assert(out.includes('role="region"'), "no region role")
  assert(out.includes('aria-label="Members"'), "no accessible name")
})
