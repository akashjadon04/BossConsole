package ai.rever.boss.layout

import ai.rever.boss.testsupport.kotlinSourcesUnder
import ai.rever.boss.testsupport.repoRoot
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Keeps the chrome bars reading [ChromeDimens] instead of literals.
 *
 * `ChromeMetricsTest` pins the arithmetic *given* a [ChromeDimens]. Nothing in it pins that the
 * chrome actually drawn and the metrics measured are the same thing, and the system fails open in
 * the direction that matters: a bar written next month as `HorizontalBar(height = 36.dp)` compiles,
 * ships, and is silently missing from the budget. That is the exact failure issue #239 opens with,
 * "a bar could be added or grown without anyone noticing".
 *
 * So this scans for it. `HorizontalBar` and `VerticalBar` are the only two ways a window chrome bar's
 * extent gets set, which makes them a small enough surface to police textually.
 *
 * A convention test in the style of `NoRawDialogConventionTest` and `SettingsSearchIndexDriftTest`,
 * and heuristic for the same reason: it cannot parse Kotlin. It reads the argument that follows the
 * call, on the same line or the next few, which is how every call site in the tree is written.
 */
class ChromeDimensConventionTest {
    private val barPrimitives =
        mapOf(
            "HorizontalBar(" to "height",
            "VerticalBar(" to "width",
        )

    /**
     * Files the scan skips.
     *
     * The two primitives declare the parameter they are scanned for, and this test quotes an
     * offending call in its own KDoc as the example of what it catches - it found itself first.
     */
    private val skippedFiles =
        setOf("HorizontalBar.kt", "VerticalBar.kt", "ChromeDimensConventionTest.kt")

    private val literal = Regex("""=\s*\d+(\.\d+)?\.dp""")

    @Test
    fun `no chrome bar sets its own extent from a literal`() {
        val offenders = mutableListOf<String>()

        kotlinSourcesUnder(repoRoot(), "composeApp/src").forEach { file ->
            if (file.name in skippedFiles) return@forEach
            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                barPrimitives.forEach { (call, parameter) ->
                    if (!line.contains(call)) return@forEach
                    // The argument sits on this line or within the next few, before the lambda opens.
                    val window = lines.subList(index, minOf(index + 6, lines.size)).joinToString("\n")
                    val argument = Regex("""$parameter\s*=[^,)\n]*""").find(window)?.value ?: return@forEach
                    if (literal.containsMatchIn(argument)) {
                        offenders += "${file.name}:${index + 1}  $argument"
                    }
                }
            }
        }

        if (offenders.isNotEmpty()) {
            fail(
                "these chrome bars set their extent from a literal instead of ChromeDimens, so they " +
                    "are drawn but not measured (see ChromeMetrics):\n" + offenders.joinToString("\n"),
            )
        }
    }

    @Test
    fun `the side panel header reads the metrics too`() {
        // BossPanelTopBar sizes a Row directly rather than going through HorizontalBar, so the scan
        // above cannot see it. It is in ChromeDimens (panelTopBarHeight) and excluded from the main
        // panel's budget, so what matters here is only that it has not drifted back to a literal.
        val file =
            kotlinSourcesUnder(repoRoot(), "composeApp/src")
                .single { it.name == "BossPanelTopBar.kt" }

        assertTrue(
            file.readText().contains("BossChrome.dimens.panelTopBarHeight"),
            "BossPanelTopBar no longer reads its height from ChromeDimens",
        )
    }

    @Test
    fun `the panel border ring reads the metrics`() {
        // The ring is 4dp off both axes of every main panel and no preference switches it off, so a
        // literal here is 4dp the budget would claim and the panel would not spend, or vice versa.
        val file =
            kotlinSourcesUnder(repoRoot(), "composeApp/src")
                .single { it.name == "BossMainWindowPanel.kt" }

        assertTrue(
            file.readText().contains("BossChrome.dimens.panelBorderThickness"),
            "BossMainWindowPanel no longer reads its border ring from ChromeDimens",
        )
    }

    @Test
    fun `the hairline the budget charges for is the hairline that is drawn`() {
        // dividerThickness was measurement-only at first: the budget added it seven times while
        // VDivider hardcoded 1.dp and the horizontal dividers took Material's default. Changing the
        // token moved the reported number and not one pixel.
        val sources = kotlinSourcesUnder(repoRoot(), "composeApp/src")

        assertTrue(
            sources
                .single { it.name == "VDivider.kt" }
                .readText()
                .contains("width(BossChrome.dimens.dividerThickness)"),
            "VDivider is back to a literal width, so the strips' hairlines are charged but not drawn",
        )

        listOf("BossTopBar.kt", "BossBottomBar.kt", "BossMainWindowPanel.kt").forEach { name ->
            assertTrue(
                sources
                    .single { it.name == name }
                    .readText()
                    .contains("thickness = BossChrome.dimens.dividerThickness"),
                "$name draws a budgeted divider without the thickness the budget charges for",
            )
        }
    }
}
