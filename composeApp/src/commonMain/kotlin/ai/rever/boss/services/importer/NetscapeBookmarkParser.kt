package ai.rever.boss.services.importer

import ai.rever.boss.services.importer.browser.isImportableUrl

/**
 * Reads the "Netscape Bookmark File" format that every browser still emits from
 * its Export Bookmarks command.
 *
 * The format nests folders as `<DL>` blocks introduced by an `<H3>` heading:
 *
 * ```html
 * <DT><H3>Work</H3>
 * <DL><p>
 *     <DT><A HREF="https://example.com">Example</A>
 *     <DT><H3>Clients</H3>
 *     <DL><p>
 *         <DT><A HREF="https://acme.test">Acme</A>
 *     </DL><p>
 * </DL><p>
 * ```
 *
 * Real exports are not well-formed XML — `<DT>` and `<p>` are routinely left
 * unclosed — so this scans tags in order and tracks folder depth rather than
 * trying to build a document tree. A full HTML parser would be a dependency for
 * no benefit.
 */
object NetscapeBookmarkParser {
    // Tags we care about, in document order. DL close is matched separately
    // because the closing tag carries no attributes.
    private val TOKEN =
        Regex(
            """<h3[^>]*>(.*?)</h3>|<a\s+([^>]*)>(.*?)</a>|<dl[^>]*>|</dl>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
    private val HREF = Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)

    /**
     * Extract every bookmark, tagging each with the slash-joined folder path it
     * was found under (null at the top level).
     */
    fun parse(html: String): List<ImportedBookmark> {
        val bookmarks = mutableListOf<ImportedBookmark>()

        // Folder names awaiting their opening <DL>. An <H3> names the folder
        // that the *next* <DL> opens, so it is staged here until then.
        var pendingFolder: String? = null
        val stack = ArrayDeque<String>()

        for (match in TOKEN.findAll(html)) {
            val text = match.value.lowercase()
            when {
                text.startsWith("<h3") -> {
                    pendingFolder = decodeEntities(stripTags(match.groupValues[1])).trim()
                }

                text.startsWith("<dl") -> {
                    // The outermost <DL> is the document root and names nothing.
                    stack.addLast(pendingFolder ?: "")
                    pendingFolder = null
                }

                text.startsWith("</dl") -> {
                    stack.removeLastOrNull()
                }

                text.startsWith("<a") -> {
                    val href =
                        HREF
                            .find(match.groupValues[2])
                            ?.groupValues
                            ?.get(1)
                            ?.trim()
                            .orEmpty()
                    if (!isImportableUrl(href)) continue

                    val title = decodeEntities(stripTags(match.groupValues[3])).trim()
                    bookmarks.add(
                        ImportedBookmark(
                            title = title.ifEmpty { href },
                            url = decodeEntities(href),
                            folder = stack.filter { it.isNotEmpty() }.joinToString("/").ifEmpty { null },
                        ),
                    )
                }
            }
        }

        return bookmarks
    }

    private fun stripTags(value: String): String = value.replace(Regex("<[^>]*>"), "")

    private fun decodeEntities(value: String): String =
        value
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&nbsp;", " ")
            // Ampersand last: decoding it first would let "&amp;lt;" collapse to "<".
            .replace("&amp;", "&")
}
