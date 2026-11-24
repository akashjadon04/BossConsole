package ai.rever.boss.config

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Asserts that the Chromium branding workflow strips the engine bundle's URL and
 * document type declarations, and does it before the re-sign.
 *
 * A source-level assertion, for the same reason `WizardDependencyReportTest` is
 * one: the workflow only runs on a mac runner against a downloaded JxBrowser
 * archive, and what it produces is verified by Launch Services on a user's
 * machine weeks later. The failure it guards is invisible and expensive - the
 * engine re-registers as a second app called "BOSS" that claims http, https and
 * `public.html`, System Settings offers two entries nobody can tell apart, and
 * every link opens a bare Chromium with no BOSS window. That is exactly the bug
 * this change exists to fix, and nothing else would notice it coming back.
 */
class EngineBundlePlistStripTest {
    private val strippedKeys = listOf("CFBundleURLTypes", "CFBundleDocumentTypes")

    private fun workflow(): String {
        val root =
            assertNotNull(
                generateSequence(File("").absoluteFile) { it.parentFile }
                    .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile },
                "could not locate the repository root",
            )
        val file = File(root, ".github/workflows/build-chromium-branding.yml")
        assertTrue(file.isFile, "build-chromium-branding.yml not found at ${file.absolutePath}")
        return file.readText()
    }

    @Test
    fun `both plist keys are deleted from the engine bundle`() {
        val text = workflow()
        strippedKeys.forEach { key ->
            assertTrue(
                text.contains("""PlistBuddy -c "Delete :$key" "${'$'}MAIN_PLIST""""),
                "the workflow must delete :$key from the engine's Info.plist",
            )
        }
    }

    @Test
    fun `the deletes happen for every macOS architecture`() {
        val text = workflow()
        // Two mac jobs, arm64 and x64, with the same steps. Patching only one
        // ships a clean engine on Apple Silicon and a colliding one on Intel.
        strippedKeys.forEach { key ->
            assertEquals(
                2,
                Regex("""PlistBuddy -c "Delete :$key"""").findAll(text).count(),
                "expected the :$key delete in both macOS build jobs",
            )
        }
    }

    @Test
    fun `the deletes precede the re-sign in every macOS job`() {
        // Split per job before comparing. Comparing first-occurrence indices
        // across the whole file constrained only the FIRST macOS job - while the
        // test above exists precisely because patching one mac job and not the
        // other is the expected mistake. So the ordering check has to be per job
        // too, or it inherits the same blind spot it was written to cover.
        val jobs = macJobSections()
        assertEquals(2, jobs.size, "expected two macOS build jobs, found ${jobs.size}")

        jobs.forEachIndexed { index, section ->
            val delete = section.indexOf("""Delete :CFBundleURLTypes""")
            val resign = section.indexOf("""Re-signing with identity""")
            assertTrue(delete > 0, "macOS job $index has no CFBundleURLTypes delete")
            assertTrue(resign > 0, "macOS job $index has no re-sign step")
            assertTrue(
                delete < resign,
                "macOS job $index: the plist edit must come BEFORE the re-sign; after it, the edit breaks " +
                    "the code signature and the engine fails codesign --verify (or worse, ships mis-signed)",
            )
        }
    }

    /**
     * The workflow text split into one section per macOS build job.
     *
     * Keyed on the job headers rather than a line count so it survives edits
     * elsewhere in the file.
     */
    private fun macJobSections(): List<String> {
        val text = workflow()
        val headers = listOf("  build-macos-arm64:", "  build-macos-x64:")
        val starts = headers.map { text.indexOf(it) }
        assertTrue(starts.all { it >= 0 }, "could not find both macOS job headers; were they renamed?")

        // Each section runs to the start of the next top-level job, so a delete
        // belonging to a later job cannot satisfy an earlier one.
        val jobStarts =
            Regex("""(?m)^  [a-z0-9-]+:$""")
                .findAll(text)
                .map { it.range.first }
                .toList()
        return starts.map { start ->
            val end = jobStarts.firstOrNull { it > start } ?: text.length
            text.substring(start, end)
        }
    }

    @Test
    fun `the end state is asserted rather than assumed`() {
        val text = workflow()
        // Every PlistBuddy delete ends in `|| true`, because an absent key is
        // fine. So without a check on the result, a delete that silently failed
        // would republish the collision - and that is the whole failure this step
        // exists to prevent.
        assertTrue(
            text.contains("""still present in the engine Info.plist"""),
            "the workflow must fail the job when either key survives the delete",
        )
    }
}
