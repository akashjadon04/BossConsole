package ai.rever.boss.plugin.browser

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * One owner for the browser's single document-start slot.
 *
 * JxBrowser allows exactly ONE `InjectJsCallback` per `Browser`, and a second
 * `set` of one **silently replaces the first** - no error, no log line, the displaced feature simply
 * stops happening. [BrowserInjectDispatcher] exists to own that slot and fan out to every
 * registered injector, and its KDoc says every injector must go through it.
 *
 * That instruction was not enough on its own. `startCoBrowseCapture` claimed the slot directly
 * anyway, and stayed the only claimant for long enough that nothing noticed. The moment
 * `setPageEventScript` arrived as a second one, sharing a tab would have switched off credential
 * capture in it (or been switched off by it, depending on which registered last).
 *
 * So this is a source check rather than a behaviour one, on purpose: the failure mode is a *second
 * call site*, and by the time a behaviour test could observe it, both features are already wired to
 * a real Chromium. Reading the source catches it at the moment it is written.
 *
 * If a genuinely new owner is ever wanted, this test is the place to argue with - do not simply
 * add a name to the allowlist.
 *
 * **Scope: every Kotlin source in the repo**, not just `composeApp`. It was `composeApp/desktopMain`
 * first, on the argument that JxBrowser is confined there - true today, and exactly the kind of
 * "true today" that makes a guard quietly stop covering the thing it was written for. Walking the
 * whole tree costs one extra second and cannot go stale.
 */
class InjectJsCallbackOwnershipTest {
    private val allowedOwners = setOf("BrowserInjectDispatcher.kt")

    private fun repoRoot(): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }

    private fun desktopSources(root: File): List<File> =
        sequenceOf("composeApp/src", "plugin-platform", "modules", "server/src")
            .map { File(root, it) }
            .filter { it.isDirectory }
            .flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            // Build output holds generated copies of the same sources; scanning them doubles the
            // walk and reports names that are not source files anyone can fix.
            .filterNot { it.path.replace(File.separatorChar, '/').contains("/build/") }
            .toList()

    /**
     * The file's code with its comments removed, then whitespace collapsed.
     *
     * Stripping comments is load-bearing rather than tidiness: both call sites that were *fixed*
     * now carry a KDoc line saying they go through the dispatcher rather than claiming the slot
     * directly, and matching raw text flags the explanation as the offence. Same reason
     * `BrowserInteractionBridgeTest` reads the collector with its comments removed. Collapsing
     * whitespace afterwards is what lets the pattern span the line break ktlint puts inside the
     * real call.
     */
    private fun codeOf(file: File): String =
        file
            .readText()
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            // Not substringBefore("//"): that cuts a line at the "//" inside "https://…" just as
            // readily as at a comment, which is a false NEGATIVE in a guard whose entire value is
            // catching one occurrence. The lookbehind keeps a scheme's slashes.
            .joinToString(" ") { line -> line.split(Regex("(?<!:)//")).first() }
            .replace(Regex("\\s+"), " ")

    @Test
    fun `only the dispatcher claims the InjectJsCallback slot`() {
        val root = assertNotNull(repoRoot(), "could not locate the repository root")
        val scanned = desktopSources(root)

        // Deliberately not a skip: a guard that passes when it cannot see the tree is decoration.
        assertTrue(scanned.size > 100, "only ${scanned.size} files scanned - the walk is not seeing the source")

        val claimants =
            scanned
                .filter { codeOf(it).contains(Regex("""\.set\( ?InjectJsCallback""")) }
                .map { it.name }
                .toSet()

        assertEquals(
            allowedOwners,
            claimants,
            "BrowserInjectDispatcher must be the only file that claims the InjectJsCallback slot. " +
                "A second claimant silently replaces the first, switching off whichever injector " +
                "registered earlier. Register through BrowserInjectDispatcher instead.",
        )
    }

    /**
     * The other half of the same trap: removing the shared callback takes it away from *every*
     * registered injector, so a handle doing that during its own teardown would tear down a feature
     * with nothing to do with it. `BrowserHandleImpl.dispose` used to, back when co-browse was the
     * only injector and the removal was therefore harmless.
     */
    @Test
    fun `nobody removes the shared InjectJsCallback slot`() {
        val root = assertNotNull(repoRoot(), "could not locate the repository root")
        val scanned = desktopSources(root)
        assertTrue(scanned.size > 100, "only ${scanned.size} files scanned - the walk is not seeing the source")

        val removers =
            scanned
                .filter { codeOf(it).contains(Regex("""\.remove\( ?InjectJsCallback""")) }
                .map { it.name }
                .sorted()

        assertTrue(
            removers.isEmpty(),
            "removes the shared InjectJsCallback slot: $removers. It is owned by " +
                "BrowserInjectDispatcher on behalf of every registered injector, so removing it " +
                "disables the others too. An injector switches itself off by clearing the state it " +
                "reads, not by unclaiming the slot.",
        )
    }

    /**
     * The scan is only worth anything if it can still see the pattern it is looking for. Both
     * checks above pass trivially if `codeOf` ever stops producing matchable text - a change to the
     * comment stripping, or ktlint reformatting the call in a way the pattern misses.
     */
    @Test
    fun `the scan can still find a claim in the file that is allowed to make one`() {
        val root = assertNotNull(repoRoot(), "could not locate the repository root")
        val dispatcher =
            File(
                root,
                "composeApp/src/desktopMain/kotlin/ai/rever/boss/plugin/browser/BrowserInjectDispatcher.kt",
            )
        assertTrue(dispatcher.isFile, "BrowserInjectDispatcher.kt is not where this test expects it")
        assertTrue(
            codeOf(dispatcher).contains(Regex("""\.set\( ?InjectJsCallback""")),
            "the pattern no longer matches the dispatcher's own claim, so the checks above prove nothing",
        )
    }
}
