/**
 * A small, safe Markdown renderer for READMEs.
 *
 * THE ONE RULE: the source is HTML-escaped BEFORE any formatting is applied, and every tag in the
 * output is one this file wrote. A README is somebody else's repository content rendered on a page
 * that also carries an administrator's control, so "render the markdown" must not become "execute
 * their document". Escaping first means a `<script>` in a README is text no matter what any of the
 * rules below do with it, rather than something a sanitiser has to be clever enough to catch.
 *
 * That is also why there is no library here. Every general Markdown renderer passes raw HTML
 * through by design - it is part of the spec - which would make the CSP the only thing between a
 * README and this page, and a CSP is a second line of defence.
 *
 * WHAT IT DOES NOT DO, so nobody assumes more than exists:
 *
 *   - Raw HTML in the source is shown as text. That is the point, not a gap.
 *   - Only `http:`, `https:` and `mailto:` links become links. A relative link (`./docs/x.md`)
 *     keeps its text and loses its href: it would resolve against this function's own origin,
 *     which is not where the document lives.
 *   - Images are rendered as links, never as `<img>`. The CSP is `img-src 'self' data:`, so a
 *     remote image would not load anyway, and allowing them would let a publisher log every
 *     reader of their plugin's page by URL. Badges therefore read as links.
 *   - Lists are flat. Nesting is parsed as further items rather than a nested list, which is
 *     wrong-looking for a deeply nested README and never wrong-dangerous.
 *   - No footnotes, no reference links, no inline HTML, no setext headings.
 */

import { esc } from "../utils/html.ts"

/** Where a README's headings start, so they sit under the page's own h1/h2 rather than beside them. */
const HEADING_OFFSET = 2

/** Schemes a link may use. Everything else keeps its text and loses its href. */
const SAFE_LINK = /^(https?:\/\/|mailto:)/i

export function renderMarkdown(source: string): string {
  // U+E000 fences the code-span placeholders below, so it must not survive from the input. It is
  // a Private Use Area codepoint: no document means anything by it, and unlike NUL it is not a
  // control character, which the lint rules rightly refuse inside a regular expression.
  const lines = source.replace(/\uE000/g, "").replace(/\r\n?/g, "\n").split("\n")

  const out: string[] = []
  let i = 0

  while (i < lines.length) {
    const line = lines[i]

    // --- fenced code ------------------------------------------------------
    const fence = /^\s*(```+|~~~+)(.*)$/.exec(line)
    if (fence) {
      const marker = fence[1][0].repeat(3)
      const body: string[] = []
      i += 1
      while (i < lines.length && !new RegExp(`^\\s*${marker}`).test(lines[i])) {
        body.push(lines[i])
        i += 1
      }
      i += 1 // the closing fence, or the end of the document
      out.push(`<pre class="md-code"><code>${esc(body.join("\n"))}</code></pre>`)
      continue
    }

    // --- heading ----------------------------------------------------------
    const heading = /^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$/.exec(line)
    if (heading) {
      const level = Math.min(heading[1].length + HEADING_OFFSET, 6)
      out.push(`<h${level}>${inline(heading[2])}</h${level}>`)
      i += 1
      continue
    }

    // --- horizontal rule --------------------------------------------------
    if (/^ {0,3}([-*_])\s*(\1\s*){2,}$/.test(line)) {
      out.push("<hr>")
      i += 1
      continue
    }

    // --- table ------------------------------------------------------------
    // A header row followed by a delimiter row. Checked before paragraphs, which would otherwise
    // swallow both as prose with pipes in it.
    if (line.includes("|") && i + 1 < lines.length && isDelimiterRow(lines[i + 1])) {
      const header = splitRow(line)
      const align = splitRow(lines[i + 1])
      i += 2
      const rows: string[][] = []
      while (i < lines.length && lines[i].includes("|") && lines[i].trim() !== "") {
        rows.push(splitRow(lines[i]))
        i += 1
      }
      out.push(table(header, align, rows))
      continue
    }

    // --- blockquote -------------------------------------------------------
    if (/^ {0,3}>/.test(line)) {
      const body: string[] = []
      while (i < lines.length && /^ {0,3}>/.test(lines[i])) {
        body.push(lines[i].replace(/^ {0,3}>\s?/, ""))
        i += 1
      }
      out.push(`<blockquote>${body.map(inline).join("<br>")}</blockquote>`)
      continue
    }

    // --- lists ------------------------------------------------------------
    const bullet = /^\s*[-*+]\s+(.*)$/
    const numbered = /^\s*\d+[.)]\s+(.*)$/
    if (bullet.test(line) || numbered.test(line)) {
      const ordered = !bullet.test(line)
      const pattern = ordered ? numbered : bullet
      const items: string[] = []
      while (i < lines.length && pattern.test(lines[i])) {
        items.push(inline(pattern.exec(lines[i])![1]))
        i += 1
      }
      const tag = ordered ? "ol" : "ul"
      out.push(`<${tag}>${items.map((it) => `<li>${it}</li>`).join("")}</${tag}>`)
      continue
    }

    // --- blank ------------------------------------------------------------
    if (line.trim() === "") {
      i += 1
      continue
    }

    // --- paragraph --------------------------------------------------------
    const paragraph: string[] = []
    while (i < lines.length && lines[i].trim() !== "" && !startsBlock(lines[i], lines[i + 1] ?? "")) {
      paragraph.push(lines[i])
      i += 1
    }
    // A paragraph that consumed nothing would spin forever; take the line and move on.
    if (paragraph.length === 0) {
      paragraph.push(lines[i])
      i += 1
    }
    out.push(`<p>${paragraph.map(inline).join("<br>")}</p>`)
  }

  return out.join("\n")
}

/** Whether a line begins some other block, so a paragraph stops before it rather than eating it. */
function startsBlock(line: string, next: string): boolean {
  return /^\s*(```+|~~~+)/.test(line) ||
    /^ {0,3}#{1,6}\s/.test(line) ||
    /^ {0,3}([-*_])\s*(\1\s*){2,}$/.test(line) ||
    /^ {0,3}>/.test(line) ||
    /^\s*[-*+]\s+/.test(line) ||
    /^\s*\d+[.)]\s+/.test(line) ||
    (line.includes("|") && isDelimiterRow(next))
}

function isDelimiterRow(line: string): boolean {
  return /^\s*\|?\s*:?-{1,}:?\s*(\|\s*:?-{1,}:?\s*)*\|?\s*$/.test(line) && line.includes("-")
}

/** Cells of one table row, with the optional leading and trailing pipes dropped. */
function splitRow(line: string): string[] {
  return line.trim().replace(/^\|/, "").replace(/\|$/, "").split("|").map((c) => c.trim())
}

function table(header: string[], align: string[], rows: string[][]): string {
  // A CLASS, not a style attribute. The CSP is `style-src 'nonce-...'` with no `unsafe-inline`,
  // so `style="text-align:right"` is simply dropped by the browser and the column silently keeps
  // its default alignment.
  const alignOf = (index: number): string => {
    const spec = align[index] ?? ""
    if (spec.startsWith(":") && spec.endsWith(":")) return ' class="md-center"'
    if (spec.endsWith(":")) return ' class="md-right"'
    return ""
  }
  const head = header.map((cell, n) => `<th${alignOf(n)}>${inline(cell)}</th>`).join("")
  const body = rows
    .map((row) => `<tr>${row.map((cell, n) => `<td${alignOf(n)}>${inline(cell)}</td>`).join("")}</tr>`)
    .join("")
  // Wrapped like every other table on these pages: a wide one scrolls inside its own region
  // instead of widening the page, and the region takes focus so a keyboard can reach the columns.
  return `<div class="scroller" tabindex="0" role="region" aria-label="Table">` +
    `<table><thead><tr>${head}</tr></thead><tbody>${body}</tbody></table></div>`
}

/**
 * Inline formatting for one run of text.
 *
 * ESCAPE FIRST. Everything below operates on text in which `<`, `>`, `&`, `"` and `'` are already
 * entities, so no rule here can be tricked into emitting a tag the source supplied - the worst a
 * clever README can do is make its own text bold.
 *
 * Code spans are lifted out before anything else and put back last, so `**not bold**` inside
 * backticks stays literal.
 */
function inline(text: string): string {
  const spans: string[] = []
  let s = esc(text).replace(/`([^`]+)`/g, (_m, code) => {
    spans.push(code)
    return `\uE000${spans.length - 1}\uE000`
  })

  // Images before links: the syntax differs by one leading `!`, so links would match these first
  // and leave a stray exclamation mark.
  s = s.replace(/!\[([^\]]*)\]\(([^)\s]+)(?:\s+&quot;[^)]*&quot;)?\)/g, (_m, alt, url) => {
    const label = alt.trim() === "" ? "image" : alt
    return SAFE_LINK.test(url)
      ? `<a href="${url}" target="_blank" rel="noopener noreferrer nofollow">${label}</a>`
      : label
  })

  s = s.replace(/\[([^\]]+)\]\(([^)\s]+)(?:\s+&quot;[^)]*&quot;)?\)/g, (_m, label, url) => {
    // A scheme this does not know keeps its text and loses its href. `javascript:` is the reason
    // the check is an allowlist rather than a list of things to refuse.
    return SAFE_LINK.test(url)
      ? `<a href="${url}" target="_blank" rel="noopener noreferrer nofollow">${label}</a>`
      : label
  })

  s = s
    .replace(/\*\*([^*]+)\*\*/g, "<strong>$1</strong>")
    .replace(/__([^_]+)__/g, "<strong>$1</strong>")
    .replace(/(^|[\s(])\*([^*\s][^*]*)\*/g, "$1<em>$2</em>")
    .replace(/(^|[\s(])_([^_\s][^_]*)_/g, "$1<em>$2</em>")
    .replace(/~~([^~]+)~~/g, "<del>$1</del>")

  return s.replace(/\uE000(\d+)\uE000/g, (_m, n) => `<code>${spans[Number(n)]}</code>`)
}
