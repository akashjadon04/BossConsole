package ai.rever.boss.services.importer.browser

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Covers the Chromium `Bookmarks` JSON reader against a fixture profile. */
class ChromiumBookmarkReaderTest {
    private lateinit var profileDir: File

    @BeforeTest
    fun setUp() {
        profileDir = Files.createTempDirectory("chromium-bookmarks-test").toFile()
    }

    @AfterTest
    fun tearDown() {
        profileDir.deleteRecursively()
    }

    private fun profile() =
        BrowserProfile(
            browserName = "Google Chrome",
            family = BrowserFamily.CHROMIUM,
            profileName = "Default",
            directory = profileDir,
        )

    private fun writeBookmarks(json: String) = File(profileDir, "Bookmarks").writeText(json)

    private val sample =
        """
        {
          "roots": {
            "bookmark_bar": {
              "type": "folder",
              "name": "Bookmarks bar",
              "children": [
                {"type": "url", "name": "Top", "url": "https://top.test/"},
                {"type": "folder", "name": "Work", "children": [
                  {"type": "url", "name": "Work Site", "url": "https://work.test/"},
                  {"type": "folder", "name": "Clients", "children": [
                    {"type": "url", "name": "Acme", "url": "https://acme.test/"}
                  ]}
                ]},
                {"type": "url", "name": "Internal", "url": "chrome://settings"},
                {"type": "url", "name": "Bookmarklet", "url": "javascript:void(0)"},
                {"type": "url", "name": "", "url": "https://untitled.test/"}
              ]
            },
            "other": {
              "type": "folder",
              "name": "Other bookmarks",
              "children": [{"type": "url", "name": "Elsewhere", "url": "https://other.test/"}]
            },
            "sync_transaction_version": "1"
          }
        }
        """.trimIndent()

    @Test
    fun `reads bookmarks under their root label`() {
        writeBookmarks(sample)

        val top = ChromiumBookmarkReader.read(profile()).single { it.url == "https://top.test/" }

        assertEquals("Bookmarks Bar", top.folder)
        assertEquals("Top", top.title)
    }

    @Test
    fun `preserves nested folder paths`() {
        writeBookmarks(sample)

        val acme = ChromiumBookmarkReader.read(profile()).single { it.url == "https://acme.test/" }

        assertEquals("Bookmarks Bar/Work/Clients", acme.folder)
    }

    @Test
    fun `maps the other root to its display name`() {
        writeBookmarks(sample)

        val other = ChromiumBookmarkReader.read(profile()).single { it.url == "https://other.test/" }

        assertEquals("Other Bookmarks", other.folder)
    }

    @Test
    fun `skips entries that are not browsable pages`() {
        writeBookmarks(sample)

        val urls = ChromiumBookmarkReader.read(profile()).map { it.url }

        assertTrue(urls.none { it.startsWith("chrome://") }, "chrome:// entries must not import")
        assertTrue(urls.none { it.startsWith("javascript:") }, "bookmarklets must not import")
    }

    @Test
    fun `falls back to the url when a bookmark has no name`() {
        writeBookmarks(sample)

        val untitled = ChromiumBookmarkReader.read(profile()).single { it.url == "https://untitled.test/" }

        assertEquals("https://untitled.test/", untitled.title)
    }

    @Test
    fun `ignores non-root keys sitting alongside the roots`() {
        // "sync_transaction_version" is a string, not a folder object.
        writeBookmarks(sample)

        assertEquals(5, ChromiumBookmarkReader.read(profile()).size)
    }

    @Test
    fun `a profile with no Bookmarks file reads as empty`() {
        assertTrue(ChromiumBookmarkReader.read(profile()).isEmpty())
        assertNull(ChromiumBookmarkReader.bookmarksFile(profile()).takeIf { it.isFile })
    }

    @Test
    fun `a Bookmarks file with no roots reads as empty`() {
        writeBookmarks("""{"checksum": "abc"}""")

        assertTrue(ChromiumBookmarkReader.read(profile()).isEmpty())
    }
}
