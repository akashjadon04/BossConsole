/**
 * The README renderer.
 *
 * The security tests come first and are the reason this file is hand-written rather than a
 * library: a README is somebody else's repository content on a page that also carries an
 * administrator's control, and every general Markdown renderer passes raw HTML through by design.
 *
 * The property under test is not "these particular attacks are caught". It is that the source is
 * escaped BEFORE any rule runs, so no rule can emit a tag the source supplied. The cases below are
 * the ones that would have to break for that to be false.
 */

import { assert, assertEquals, assertStringIncludes } from "@std/assert"
import { renderMarkdown } from "../services/markdown.ts"

/** No `<` in the output that this renderer did not write itself. */
const OUR_TAGS =
  /<\/?(?:h[1-6]|p|br|hr|ul|ol|li|pre|code|blockquote|strong|em|del|a|table|thead|tbody|tr|th|td|div)(?:\s[^>]*)?>/g

function foreignTags(html: string): string[] {
  return (html.replace(OUR_TAGS, "").match(/<[^>]*>/g) ?? [])
}

// ---------------------------------------------------------------------------
// Nothing from the source becomes markup
// ---------------------------------------------------------------------------

Deno.test("a script tag in a README is text", () => {
  const html = renderMarkdown("Hello\n\n<script>alert(1)</script>\n")
  assertEquals(html.includes("<script"), false)
  assertStringIncludes(html, "&lt;script&gt;")
  assertEquals(foreignTags(html), [])
})

Deno.test("an event handler on a fabricated tag cannot survive", () => {
  const html = renderMarkdown("<img src=x onerror=alert(1)>")
  assertEquals(/<img/i.test(html), false)
  assertEquals(foreignTags(html), [])
})

Deno.test("markdown emphasis inside a fabricated tag does not reassemble it", () => {
  // The rule that worried me most: emphasis rewrites the text after escaping, so a source that
  // hides a tag's angle brackets behind entities must not be able to get them back.
  const html = renderMarkdown("**<**script**>**alert(1)**<**/script**>**")
  assertEquals(html.includes("<script"), false)
  assertEquals(foreignTags(html), [])
})

Deno.test("a javascript: link keeps its text and loses its href", () => {
  const html = renderMarkdown("[click me](javascript:alert(1))")
  assertEquals(html.includes("javascript:"), false)
  assertStringIncludes(html, "click me")
  assertEquals(html.includes("<a "), false)
})

Deno.test("a data: link is refused too", () => {
  const html = renderMarkdown("[x](data:text/html;base64,PHNjcmlwdD4=)")
  assertEquals(html.includes("<a "), false)
})

Deno.test("a quote in a link cannot break out of the href attribute", () => {
  const html = renderMarkdown('[x](https://e.test/a"onmouseover="alert(1))')
  assertEquals(html.includes('"onmouseover="'), false)
  assertEquals(foreignTags(html), [])
})

Deno.test("an image is a link, never an img element", () => {
  // img-src is `'self' data:`, so a remote image would not load; allowing them would also let a
  // publisher log every reader of their plugin's page by URL.
  const html = renderMarkdown("![build](https://img.shields.io/badge/build-passing.svg)")
  assertEquals(/<img/i.test(html), false)
  assertStringIncludes(html, '<a href="https://img.shields.io/badge/build-passing.svg"')
  assertStringIncludes(html, ">build</a>")
})

Deno.test("a table cell cannot smuggle markup either", () => {
  const html = renderMarkdown("| a | b |\n|---|---|\n| <script>x</script> | y |")
  assertStringIncludes(html, "<table>")
  assertEquals(html.includes("<script"), false)
  assertEquals(foreignTags(html), [])
})

Deno.test("a code fence shows its contents rather than running them", () => {
  const html = renderMarkdown("```html\n<script>alert(1)</script>\n```")
  assertStringIncludes(html, "<pre class=\"md-code\"><code>")
  assertEquals(html.includes("<script>alert(1)</script>"), false)
  assertStringIncludes(html, "&lt;script&gt;")
})

// ---------------------------------------------------------------------------
// It actually renders markdown
// ---------------------------------------------------------------------------

Deno.test("headings render, offset below the page's own", () => {
  // The page owns h1 and h2. A README starting at h1 would otherwise claim the document outline.
  const html = renderMarkdown("# Title\n\n## Section\n\n###### Deep")
  assertStringIncludes(html, "<h3>Title</h3>")
  assertStringIncludes(html, "<h4>Section</h4>")
  assertStringIncludes(html, "<h6>Deep</h6>")
  assertEquals(html.includes("<h1>"), false)
})

Deno.test("emphasis, strong, strikethrough and code spans render", () => {
  const html = renderMarkdown("*a* **b** ~~c~~ `d`")
  assertStringIncludes(html, "<em>a</em>")
  assertStringIncludes(html, "<strong>b</strong>")
  assertStringIncludes(html, "<del>c</del>")
  assertStringIncludes(html, "<code>d</code>")
})

Deno.test("a code span protects its contents from emphasis", () => {
  const html = renderMarkdown("`**not bold**`")
  assertStringIncludes(html, "<code>**not bold**</code>")
  assertEquals(html.includes("<strong>"), false)
})

Deno.test("an underscore inside a word does not italicise it", () => {
  // snake_case_names are everywhere in these READMEs, and CommonMark leaves them alone.
  const html = renderMarkdown("call user_can_view_plugin_row first")
  assertEquals(html.includes("<em>"), false)
  assertStringIncludes(html, "user_can_view_plugin_row")
})

Deno.test("lists render as lists", () => {
  const bullets = renderMarkdown("- one\n- two\n")
  assertStringIncludes(bullets, "<ul><li>one</li><li>two</li></ul>")
  const numbered = renderMarkdown("1. one\n2. two\n")
  assertStringIncludes(numbered, "<ol><li>one</li><li>two</li></ol>")
})

Deno.test("a table renders with its header, alignment and a scroll region", () => {
  const html = renderMarkdown("| Setting | Default |\n|---|---:|\n| Model | glm-4.6 |")
  assertStringIncludes(html, "<th>Setting</th>")
  assertStringIncludes(html, '<th class="md-right">Default</th>')
  assertStringIncludes(html, "<td>Model</td>")
  // Wrapped like every other table on these pages, and focusable so a keyboard can scroll it.
  assertStringIncludes(html, '<div class="scroller" tabindex="0" role="region"')
  // A class, not a style attribute: style-src is nonce-only, so inline styles never apply.
  assertEquals(html.includes("style="), false)
})

Deno.test("links render with the attributes an outbound link needs", () => {
  const html = renderMarkdown("see [the docs](https://example.test/docs)")
  assertStringIncludes(html, '<a href="https://example.test/docs"')
  assertStringIncludes(html, 'rel="noopener noreferrer nofollow"')
})

Deno.test("blockquotes and rules render", () => {
  assertStringIncludes(renderMarkdown("> quoted"), "<blockquote>quoted</blockquote>")
  assertStringIncludes(renderMarkdown("---"), "<hr>")
})

Deno.test("paragraphs are separated and single newlines break", () => {
  const html = renderMarkdown("one\ntwo\n\nthree")
  assertStringIncludes(html, "<p>one<br>two</p>")
  assertStringIncludes(html, "<p>three</p>")
})

Deno.test("a relative link keeps its text and loses its href", () => {
  // It would resolve against this function's origin, which is not where the document lives.
  const html = renderMarkdown("[contributing](./CONTRIBUTING.md)")
  assertStringIncludes(html, "contributing")
  assertEquals(html.includes("<a "), false)
})

// ---------------------------------------------------------------------------
// It terminates
// ---------------------------------------------------------------------------

Deno.test("odd input renders without hanging or throwing", () => {
  // The block loop advances on every branch; these are the shapes where that was least obvious.
  for (
    const source of [
      "",
      "\n\n\n",
      "```\nunclosed fence",
      "| a | b |",
      "|---|---|",
      ">",
      "-",
      "#",
      "####### seven hashes",
      "*".repeat(500),
      "`".repeat(200),
      "| a |\n|---|\n".repeat(50),
    ]
  ) {
    const html = renderMarkdown(source)
    assert(typeof html === "string")
    assertEquals(foreignTags(html), [], `foreign markup from: ${source.slice(0, 20)}`)
  }
})

Deno.test("a README cannot forge the code-span placeholder", () => {
  // Code spans are lifted out and replaced by a U+E000-fenced index, then put back last. A source
  // that writes that fence itself would otherwise splice `undefined` into the output, or worse,
  // reach a span it did not capture. The input is stripped of U+E000 before anything runs.
  const forged = "\uE0000 and `real`"
  const html = renderMarkdown(forged)
  assertEquals(html.includes("\uE000"), false, "the placeholder fence survived into the output")
  assertEquals(html.includes("undefined"), false, "a forged index reached the restore step")
  assertStringIncludes(html, "<code>real</code>")
  assertEquals(foreignTags(html), [])
})
