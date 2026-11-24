package ai.rever.boss.components.overlays

import java.io.File
import kotlin.test.Test
import kotlin.test.fail

/**
 * Asserts every `BossDialog` call site gives its card an intrinsic width rather than filling.
 *
 * `BossDialog`'s contract is that its content is "a self-contained, intrinsically-sized card", and
 * the two paths punish a filling card differently:
 *
 *  - **Lightweight** (`androidx.compose.ui.window.Dialog`, i.e. Settings, the first-run window, and
 *    every window with no browser surface) caps content at Compose's platform default dialog width -
 *    580.dp on a normal window, 440.dp or 320.dp on a small one. A `fillMaxWidth()` card therefore
 *    looks plausible here, which is why one shipped.
 *  - **Heavyweight** (the main window under HARDWARE_ACCELERATED) measures the card inside a
 *    `fillMaxSize()` scrim spanning the whole window, and ignores `usePlatformDefaultWidth`. The same
 *    card becomes a band across the entire screen - which is exactly what the logout dialog did.
 *
 * A convention test rather than review vigilance, for the same reason as [NoRawDialogConventionTest]:
 * nothing in the diff looks wrong, and whether the author sees the bug depends on which window they
 * happened to open the dialog from. Two of 35 call sites had it.
 *
 * Deliberately a heuristic, and it cannot parse Kotlin. It reads the [SCAN_LINES] lines after each
 * `BossDialog(` and requires a width modifier to appear before any fill modifier. "Width first"
 * rather than "no fill at all" is the rule on purpose: a fixed-width card whose inner content fills
 * it is correct and common (`ShortcutTestDialog` is `width(900.dp)` then `fillMaxSize()`), so only a
 * fill reached BEFORE any width is an offence.
 */
class DialogCardWidthConventionTest {
    private val allowed =
        setOf(
            // The primitive itself: its own scrim is the fillMaxSize() this rule is about.
            "BossDialog.kt",
            // This file. It holds both the needle and the fill modifiers as string constants, so it
            // matches its own rule.
            "DialogCardWidthConventionTest.kt",
        )

    @Test
    fun `every BossDialog card declares a width before it fills`() {
        val root = repoRoot()
        val roots =
            listOf(File(root, "composeApp/src"), File(root, "plugin-platform"))
                .filter { it.isDirectory }
        check(roots.isNotEmpty()) { "no source roots found under $root" }

        val offenders =
            roots
                .flatMap { it.walkTopDown().filter { f -> f.isFile && f.extension == "kt" } }
                .filter { it.name !in allowed }
                .flatMap { file -> offencesIn(file, root) }
                .sorted()

        if (offenders.isNotEmpty()) {
            fail(
                "These BossDialog call sites size their card by filling instead of declaring a " +
                    "width, so on the heavyweight path (the main window) the card stretches to the " +
                    "full window width. Give it a fixed .width(...) - 400.dp is the house " +
                    "confirmation width, see ConfirmationDialog:\n  " + offenders.joinToString("\n  "),
            )
        }
    }

    /** Every `BossDialog(` in [file] whose card fills before it declares a width. */
    private fun offencesIn(
        file: File,
        root: File,
    ): List<String> {
        val lines = file.readLines()
        return lines.indices
            .filter { "BossDialog(" in lines[it] }
            // A call site, not the import or a KDoc reference to the name.
            .filter { !lines[it].trimStart().startsWith("import ") && !lines[it].trimStart().startsWith("*") }
            .filter { start ->
                val window = lines.subList(start, minOf(start + SCAN_LINES, lines.size))
                val fill = window.indexOfFirst { FILL in it || FILL_SIZE in it }
                val width = window.indexOfFirst { WIDTH in it || WIDTH_IN in it || REQUIRED_WIDTH in it }
                fill >= 0 && (width < 0 || fill < width)
            }.map { "${file.relativeTo(root).path}:${it + 1}" }
    }

    /** Walks up from the test's working directory to the checkout root. */
    private fun repoRoot(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            if (File(dir, "composeApp").isDirectory && File(dir, "version.properties").isFile) return dir
            dir = dir.parentFile
        }
        fail("could not locate the repository root from ${File(".").absolutePath}")
    }

    private companion object {
        /**
         * How far past `BossDialog(` to look for the card's sizing.
         *
         * Wide enough to clear a multi-line `DialogProperties(...)` block plus the card's modifier
         * chain (the longest real call site needs about 20), short enough not to wander into nested
         * composables further down the content lambda.
         */
        const val SCAN_LINES = 22
        const val FILL = ".fillMaxWidth("
        const val FILL_SIZE = ".fillMaxSize("
        const val WIDTH = ".width("
        const val WIDTH_IN = ".widthIn("
        const val REQUIRED_WIDTH = ".requiredWidth("
    }
}
