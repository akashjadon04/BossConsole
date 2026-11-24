package ai.rever.boss.app

import ai.rever.boss.components.buttons.TOOL_LAUNCHER_TAG
import ai.rever.boss.window.TabBarVerticalWidthRange
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that the host's actions stay ON SCREEN at the narrowest the vertical tab bar goes.
 *
 * The bar is user-resizable down to [TabBarVerticalWidthRange].start, 120dp. Four 32dp buttons
 * with 4dp between them and 8dp either side need 156dp; three need exactly 120, with no margin.
 *
 * A `Row` does not wrap. Measured at 120dp, what it actually did was hand the last child ZERO
 * width - Search came back as `Rect(0, 0, 0, 0)` while the other three kept their full 32dp - so
 * Search simply was not there, on a width the user can reach by dragging.
 *
 * That measured shape is why these assertions check each icon's SIZE and not just that it sits
 * between the bar's edges: a zero-width rect at the origin is inside every bounds check that will
 * ever be written, so the first version of this test passed against the very bug it was added for.
 *
 * Nothing else would have caught it either: every placement test here is on the pure function,
 * which answers "the bar has a foot" perfectly well while the foot is too narrow to show what is
 * in it.
 */
class VerticalBarHostActionsLayoutTest {
    @get:Rule
    val rule = createComposeRule()

    private val narrowest = TabBarVerticalWidthRange.start.dp

    private fun mountBar(
        width: Dp,
        withLauncher: Boolean,
    ) {
        val actions =
            focusQuickActionsFooter(
                placement = FocusQuickActionsPlacement.TAB_BAR_FOOTER,
                onShowSettings = {},
                onShowSearch = {},
                onSignOut = {},
                toolLauncher =
                    if (!withLauncher) {
                        null
                    } else {
                        { hintDirection, modifier ->
                            ai.rever.boss.components.buttons.ToolLauncherButton(
                                onClick = {},
                                hintDirection = hintDirection,
                                modifier = modifier,
                            )
                        }
                    },
            )
        rule.setContent {
            // clipToBounds mirrors the bar, which is what turns an overflow into a vanished
            // button rather than one drawn outside its column.
            Column(modifier = Modifier.width(width).clipToBounds().testTag(BAR_TAG)) {
                VerticalBarHostActions(actions)
            }
        }
        rule.waitForIdle()
    }

    private fun barBounds(): Rect = rule.onNodeWithTag(BAR_TAG).fetchSemanticsNode().boundsInRoot

    /** Present at its full size AND within the bar's edges. Either alone passes the bug. */
    private fun assertShown(contentDescription: String) {
        val bar = barBounds()
        val icon = rule.onNodeWithContentDescription(contentDescription).fetchSemanticsNode().boundsInRoot
        val expected = with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }

        assertEquals(expected, icon.width, "$contentDescription is ${icon.width}px wide, expected $expected")
        assertEquals(expected, icon.height, "$contentDescription is ${icon.height}px tall, expected $expected")
        assertTrue(
            icon.left >= bar.left && icon.right <= bar.right,
            "$contentDescription spans ${icon.left}..${icon.right}, outside the bar's ${bar.left}..${bar.right}",
        )
    }

    @Test
    fun `all four actions stay inside the narrowest bar`() {
        mountBar(width = narrowest, withLauncher = true)

        // The two the old Row clipped first, plus the two it kept.
        assertShown("Sign Out")
        assertShown("Search")
        assertShown("Settings")
        assertShown("Tools")

        // By tag as well as by label: TOOL_LAUNCHER_TAG existed with no consumer anywhere, and a
        // tag nothing reads is a tag that can be renamed or dropped without a failure.
        val launcher = rule.onNodeWithTag(TOOL_LAUNCHER_TAG).fetchSemanticsNode().boundsInRoot
        val expected = with(rule.density) { SIDEBAR_ICON_SIZE.toPx() }
        assertEquals(expected, launcher.width, "the launcher's own tag must find it at full size")
    }

    @Test
    fun `three actions stay inside the narrowest bar`() {
        // Exactly 120dp of content in a 120dp bar: it fitted, but with nothing to spare, so any
        // change to the padding or the icon size would have pushed it over without a signal.
        mountBar(width = narrowest, withLauncher = false)

        assertShown("Sign Out")
        assertShown("Search")
        assertShown("Settings")
    }

    @Test
    fun `a comfortable bar still lays them out in one row`() {
        // Wrapping is the fallback, not the shape: at the default width they belong side by side.
        mountBar(width = 200.dp, withLauncher = true)

        val signOut = rule.onNodeWithContentDescription("Sign Out").fetchSemanticsNode().boundsInRoot
        val search = rule.onNodeWithContentDescription("Search").fetchSemanticsNode().boundsInRoot
        assertTrue(signOut.top == search.top, "expected one row, got tops ${signOut.top} and ${search.top}")
    }
}

private const val BAR_TAG = "vertical-bar-host-actions-layout-test-bar"
