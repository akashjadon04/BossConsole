package ai.rever.boss.components.windows

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asserts every settings-section string written as a **literal** in main source
 * still names a real [SettingsSection].
 *
 * These literals exist because `SettingsSection` lives in desktopMain and their
 * call sites are commonMain, which cannot see the enum. What makes them worth a
 * test rather than a comment is how they fail: [resolveSettingsDeepLink] answers
 * `Unresolved` for a name no section claims, and `Unresolved` is *deliberately* a
 * no-op for an already-open Settings window - so a renamed section leaves the
 * menu entry raising the window and doing nothing else, with a `logger.debug`
 * line as the only record. Nothing crashes and nothing turns red; the entry just
 * quietly stops working.
 *
 * Scanning the source is not a proxy here - the literal in the source **is** the
 * thing that can drift. A test naming the three current strings would be a fourth
 * copy of them, and would keep passing after someone edited the real one.
 *
 * Callers that *can* see the enum should name it instead (`BossWindow`'s Help
 * menu does: `SettingsSection.DEFAULT_APPS.name`), which turns the same mistake
 * into a compile error and contributes nothing for this test to find.
 */
class SettingsSectionLiteralsTest {
    private fun repoRoot(): File =
        assertNotNull(
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
            "could not locate the repository root",
        )

    /**
     * Both ways a section is named: the cross-window event, and the state object
     * the collector calls. Test sources are excluded by the caller - they name
     * sections deliberately, including strings that must NOT resolve.
     */
    private val callPattern = Regex("""(?:triggerOpenSettings|settingsWindow\.open)\(([^)]*)\)""")

    /** A plain string literal. Templates and escapes are not section names. */
    private val literalPattern = Regex(""""([^"$\\]*)"""")

    /** Section literals in [text], in source order. */
    private fun sectionLiterals(text: String): List<String> =
        callPattern
            .findAll(text)
            .flatMap { call -> literalPattern.findAll(call.groupValues[1]) }
            .map { it.groupValues[1] }
            .toList()

    private fun mainSources(): List<File> =
        listOf("commonMain", "desktopMain")
            .map { File(repoRoot(), "composeApp/src/$it") }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" } }

    @Test
    fun `every section literal in main source names a real section`() {
        val found = mainSources().flatMap { file -> sectionLiterals(file.readText()).map { file to it } }

        // The scanner finding nothing would pass the assertion below while proving
        // nothing at all - the failure mode of every source-scanning test.
        assertTrue(found.isNotEmpty(), "found no section literals at all; the scanner is broken, not the source")

        found.forEach { (file, literal) ->
            assertTrue(
                resolveSettingsDeepLink(literal, emptySet()) is SettingsDeepLink.Section,
                "${file.name} names \"$literal\", which no SettingsSection claims - so that call raises the " +
                    "Settings window and navigates nowhere. Rename it to a current section, or have the caller " +
                    "name the enum if it is in desktopMain.",
            )
        }
    }

    @Test
    fun `the scanner extracts a literal from either call shape and ignores non-literals`() {
        // Self-check, on a fixture rather than on the tree: a regex that silently
        // stopped matching would make the test above vacuous, and the assertion
        // that it found *something* cannot tell "found all of them" from "found one".
        val fixture =
            """
            MenuActionsHandler.triggerOpenSettings(id, "SIDEBAR")
            state.settingsWindow.open("KEYMAP")
            MenuActionsHandler.triggerOpenSettings(
                windowId = windowState.id,
                section = SettingsSection.DEFAULT_APPS.name,
            )
            MenuActionsHandler.triggerOpenSettings(windowId, section)
            state.settingsWindow.open()
            """.trimIndent()

        // The enum reference and the forwarded parameter are deliberately absent:
        // neither can drift into naming a section that does not exist.
        assertEquals(listOf("SIDEBAR", "KEYMAP"), sectionLiterals(fixture))
    }

    @Test
    fun `a section that no longer exists is caught`() {
        // Reverse-verification: the assertion above only means something if this
        // is what a stale literal looks like to it.
        assertTrue(resolveSettingsDeepLink("LLM_PROVIDERS", emptySet()) is SettingsDeepLink.Unresolved)
        assertTrue(sectionLiterals("""triggerOpenSettings(id, "LLM_PROVIDERS")""") == listOf("LLM_PROVIDERS"))
    }
}
