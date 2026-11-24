package ai.rever.boss.services.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fixtures here mirror what browsers actually emit — unclosed `<DT>`, unclosed
 * `<p>`, entity-escaped titles — rather than well-formed XML.
 */
class NetscapeBookmarkParserTest {
    private val chromeExport =
        """
        <!DOCTYPE NETSCAPE-Bookmark-file-1>
        <META HTTP-EQUIV="Content-Type" CONTENT="text/html; charset=UTF-8">
        <TITLE>Bookmarks</TITLE>
        <H1>Bookmarks</H1>
        <DL><p>
            <DT><A HREF="https://top.test/" ADD_DATE="1700000000">Top Level</A>
            <DT><H3 ADD_DATE="1700000000">Work</H3>
            <DL><p>
                <DT><A HREF="https://work.test/" ADD_DATE="1700000000">Work Site</A>
                <DT><H3 ADD_DATE="1700000000">Clients</H3>
                <DL><p>
                    <DT><A HREF="https://acme.test/" ADD_DATE="1700000000">Acme &amp; Co</A>
                </DL><p>
            </DL><p>
            <DT><A HREF="javascript:void(0)">A Bookmarklet</A>
        </DL><p>
        """.trimIndent()

    @Test
    fun `reads a top-level bookmark with no folder`() {
        val top = NetscapeBookmarkParser.parse(chromeExport).single { it.url == "https://top.test/" }

        assertNull(top.folder)
        assertEquals("Top Level", top.title)
    }

    @Test
    fun `recovers a one-level folder`() {
        val work = NetscapeBookmarkParser.parse(chromeExport).single { it.url == "https://work.test/" }

        assertEquals("Work", work.folder)
    }

    @Test
    fun `recovers a nested folder path`() {
        val acme = NetscapeBookmarkParser.parse(chromeExport).single { it.url == "https://acme.test/" }

        assertEquals("Work/Clients", acme.folder)
    }

    @Test
    fun `decodes HTML entities in titles`() {
        val acme = NetscapeBookmarkParser.parse(chromeExport).single { it.url == "https://acme.test/" }

        assertEquals("Acme & Co", acme.title)
    }

    @Test
    fun `skips bookmarklets`() {
        // A javascript: entry is not a page; imported as a tab it could never open.
        val urls = NetscapeBookmarkParser.parse(chromeExport).map { it.url }

        assertTrue(urls.none { it.startsWith("javascript:") })
    }

    @Test
    fun `handles an export that never closes its DT or p tags`() {
        val sloppy =
            """
            <DL><p>
                <DT><H3>Folder</H3>
                <DL><p>
                    <DT><A HREF="https://one.test/">One</A>
                    <DT><A HREF="https://two.test/">Two</A>
                </DL><p>
            </DL><p>
            """.trimIndent()

        val parsed = NetscapeBookmarkParser.parse(sloppy)

        assertEquals(2, parsed.size)
        assertTrue(parsed.all { it.folder == "Folder" })
    }

    @Test
    fun `falls back to the url when a title is empty`() {
        val parsed = NetscapeBookmarkParser.parse("""<DL><p><DT><A HREF="https://x.test/"></A></DL><p>""")

        assertEquals("https://x.test/", parsed.single().title)
    }

    @Test
    fun `returns nothing for an empty document`() {
        assertTrue(NetscapeBookmarkParser.parse("").isEmpty())
    }

    @Test
    fun `leaving a folder restores the parent path`() {
        // Regression guard for the depth stack: an entry after a closed folder
        // must not inherit that folder's name.
        val after =
            NetscapeBookmarkParser.parse(
                """
                <DL><p>
                    <DT><H3>Inner</H3>
                    <DL><p>
                        <DT><A HREF="https://inside.test/">Inside</A>
                    </DL><p>
                    <DT><A HREF="https://outside.test/">Outside</A>
                </DL><p>
                """.trimIndent(),
            )

        assertEquals("Inner", after.single { it.url == "https://inside.test/" }.folder)
        assertNull(after.single { it.url == "https://outside.test/" }.folder)
    }
}
