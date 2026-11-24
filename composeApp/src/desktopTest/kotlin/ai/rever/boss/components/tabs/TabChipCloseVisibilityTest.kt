package ai.rever.boss.components.tabs

// Deliberately not in the chip's own package: that one has underscores in its name, which detekt's
// PackageNaming rule rejects for anything new. `internal` is module-wide, so the chip is reachable
// from here regardless.
import ai.rever.boss.components.window_panel.components.main_window_panels.TabFaviconChip
import ai.rever.boss.plugin.api.TabInfo
import ai.rever.boss.plugin.api.TabTypeId
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Language
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import org.junit.Test
import kotlin.test.assertEquals

/**
 * Pins that a closable chip shows its cross without being hovered.
 *
 * It used to appear on hover, which had two costs. The cross was invisible until you went looking
 * for it, so whether a tab could be closed from the strip at all was not discoverable; and the chip
 * changed WIDTH when it appeared, so the strip reflowed under the pointer that was reaching for it.
 *
 * A composition test rather than a unit one because "is it on screen" is the whole claim, and the
 * old behaviour would pass any test that only asked whether a cross exists.
 */
@OptIn(ExperimentalTestApi::class)
class TabChipCloseVisibilityTest {
    @Test
    fun `a closable chip shows its cross with no hover`() =
        runComposeUiTest {
            setContent {
                Row {
                    TabFaviconChip(tab = TestTab("tab-1"), isActive = false, onClick = {}, onClose = {})
                }
            }

            // Never hovered, never clicked - just mounted.
            onNodeWithContentDescription(CLOSE_LABEL).assertExists()
        }

    @Test
    fun `a chip that cannot be closed shows no cross`() =
        runComposeUiTest {
            setContent {
                Row {
                    TabFaviconChip(tab = TestTab("tab-1"), isActive = false, onClick = {})
                }
            }

            onAllNodesWithContentDescription(CLOSE_LABEL).assertCountEquals(0)
        }

    @Test
    fun `the cross closes its own tab`() =
        runComposeUiTest {
            var closed = 0
            setContent {
                Row {
                    TabFaviconChip(tab = TestTab("tab-1"), isActive = false, onClick = {}, onClose = { closed++ })
                }
            }

            onNodeWithContentDescription(CLOSE_LABEL).performClick()
            assertEquals(1, closed)
        }

    @Test
    fun `every closable chip in a strip shows one`() =
        runComposeUiTest {
            // The reason this matters: with hover-to-reveal, exactly one cross could be visible at
            // a time - the one under the pointer.
            setContent {
                Row {
                    repeat(3) { index ->
                        TabFaviconChip(tab = TestTab("tab-$index"), isActive = index == 0, onClick = {}, onClose = {})
                    }
                }
            }

            onAllNodesWithContentDescription(CLOSE_LABEL).assertCountEquals(3)
        }
}

private const val CLOSE_LABEL = "Close tab"

private data class TestTab(
    override val id: String,
    override val typeId: TabTypeId = TabTypeId("chip-test", "test.plugin"),
    override val title: String = "Chip Test Tab",
) : TabInfo {
    override val icon get() = Icons.Outlined.Language
}
