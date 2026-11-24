package ai.rever.boss.filetypes

import ai.rever.boss.components.plugin.language.EditorLanguages
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the runtime half of the file-type table.
 *
 * `buildSrc/BossFileTypesTest` covers the generator and the plist it produces.
 * This covers what the *app* does with the same resource, and one thing neither
 * side can check alone: that the exported UTIs the app asks Launch Services about
 * are the identifiers the plist actually declared. Those are built independently
 * on the two sides - a mismatch would leave BOSS declaring
 * `ai.rever.boss.kotlin-source` in its bundle and asking the OS about something
 * else, which reports "not the default" forever and no button can fix.
 */
class FileTypeCategoriesTest {
    private val table get() = FileTypeCategories.table

    @Test
    fun `the resource loads from the classpath`() {
        assertTrue(FileTypeCategories.isAvailable(), "boss-file-types.json did not load")
        assertTrue(table.categories.isNotEmpty())
        assertTrue(table.extensions.isNotEmpty())
    }

    @Test
    fun `the table covers exactly the extensions EditorLanguages knows`() {
        // The anti-drift gate, from the app's side. Its sibling in buildSrc reads
        // EditorLanguages out of the source with a regex because it compiles
        // first; here the real object is on the classpath, so this is the
        // stronger of the two and the one to trust.
        assertEquals(
            EditorLanguages.extensions().keys.sorted(),
            table.extensions.map { it.ext }.sorted(),
            "boss-file-types.json and EditorLanguages.EXTENSIONS disagree about which extensions exist",
        )
    }

    @Test
    fun `the table agrees with EditorLanguages about each extension's language`() {
        val languages = EditorLanguages.extensions()
        table.extensions.forEach { row ->
            assertEquals(
                languages[row.ext],
                row.language,
                "boss-file-types.json calls .${row.ext} '${row.language}'",
            )
        }
    }

    @Test
    fun `exported type ids follow the resource's own naming`() {
        // Read from the resource rather than reconstructed here, which is the
        // point: both the generator and the app derive them from these two
        // fields, so this test fails if either side invents its own.
        table.categories.forEach { category ->
            table.exportedTypeIdsFor(category.id).forEach { id ->
                assertTrue(id.startsWith("${table.exportedTypePrefix}."), id)
                assertTrue(id.endsWith(table.exportedTypeSuffix), id)
            }
        }
    }

    @Test
    fun `one exported type per language, not one per extension`() {
        // The plist declares `ai.rever.boss.kotlin-source` once for .kt and
        // .kts. Asking about one id per extension would ask about types that do
        // not exist.
        val kotlinIds = table.exportedTypeIdsFor("source-code").filter { it.contains("kotlin") }
        assertEquals(1, kotlinIds.size, "expected exactly one Kotlin type, got $kotlinIds")
    }

    @Test
    fun `content types for a category include both claimed and exported types`() {
        val sourceTypes = table.contentTypesFor("source-code")
        // A claimed system UTI.
        assertTrue("public.python-script" in sourceTypes, "expected the Python UTI among $sourceTypes")
        // One BOSS exports.
        assertTrue(sourceTypes.any { it.contains("kotlin") }, "expected a Kotlin type among $sourceTypes")
        // No duplicates, since several extensions share one UTI.
        assertEquals(sourceTypes.distinct().size, sourceTypes.size)
    }

    @Test
    fun `the three vetted system types are never asked about`() {
        // Measured facts, not guesses: .ts resolves to a video container, .as to
        // a binary archive and .edn to an Adobe project format. Claiming any
        // would make BOSS the default application for a format it cannot open.
        val everyType = table.categories.flatMap { table.contentTypesFor(it.id) }.toSet()
        listOf("public.mpeg-2-transport-stream", "com.apple.applesingle-archive", "com.adobe.edn").forEach {
            assertFalse(it in everyType, "$it must never be claimed")
        }
    }

    @Test
    fun `the browser category claims the schemes and the web page category does not`() {
        assertEquals(listOf("http", "https"), table.schemesFor("web-links"))
        assertTrue(table.schemesFor("markdown").isEmpty())
        assertTrue(table.schemesFor("source-code").isEmpty())
    }

    @Test
    fun `the browser category is schemes with no extensions, which is why a status must read both`() {
        // The fact that made the Windows status wrong. `WindowsFileTypeHandler`
        // read only the per-extension `UserChoice` keys, so `web-links` - schemes
        // and nothing else - reported Other on every machine, including one where
        // BOSS did hold http and https, and `register` returned early without ever
        // adding BOSS to the browser list its own settings page sends the user to.
        //
        // Pinned here rather than in that handler because the handler shells out to
        // `reg`, which no mac or Linux runner can answer. If a later change gives
        // this category an extension or drops its schemes, the reasoning behind
        // reading both sides changes with it, and this is where that shows up.
        assertTrue(table.extensionsFor("web-links").isEmpty(), "web-links gained an extension")
        assertTrue(table.schemesFor("web-links").isNotEmpty(), "web-links lost its schemes")

        // And the converse, which is why the extension side cannot simply be
        // dropped: web-pages is extensions with no schemes.
        assertTrue(table.extensionsFor("web-pages").isNotEmpty())
        assertTrue(table.schemesFor("web-pages").isEmpty())
    }

    @Test
    fun `every category has Linux MIME types or schemes to claim`() {
        // A category with neither is a row in Settings whose Set button cannot do
        // anything on Linux.
        table.categories.forEach { category ->
            val claimsSomething = category.mimeTypes.isNotEmpty() || category.schemes.isNotEmpty()
            assertTrue(claimsSomething, "category '${category.id}' claims nothing on Linux")
        }
    }

    @Test
    fun `markdown and shell scripts claim their system types`() {
        assertTrue("net.daringfireball.markdown" in table.contentTypesFor("markdown"))
        val shell = table.contentTypesFor("shell-scripts")
        assertTrue("public.shell-script" in shell)
        assertTrue("public.bash-script" in shell)
        assertTrue("public.zsh-script" in shell)
    }

    @Test
    fun `an unknown category id answers empty rather than throwing`() {
        // Read from a resource, so a stale id from a persisted setting must not
        // take the Settings screen down.
        assertTrue(table.contentTypesFor("no-such-category").isEmpty())
        assertTrue(table.extensionsFor("no-such-category").isEmpty())
        assertTrue(table.mimeTypesFor("no-such-category").isEmpty())
    }
}
