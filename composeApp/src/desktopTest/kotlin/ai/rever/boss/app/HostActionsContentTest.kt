package ai.rever.boss.app

import ai.rever.boss.components.buttons.ToolboxButton
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.plugin.api.SidebarItem
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what the host's own action row contains, and in what order.
 *
 * One list feeds four places - the top bar, the right rail, the vertical bar's foot and the
 * floating cluster - so this is where "the Toolbox sits beside Settings" is true or not, for all
 * of them at once.
 *
 * The count matters beyond the ordering: [FOCUS_QUICK_ACTION_COUNT] is what the right rail
 * subtracts from its own height before dealing icon rows to the plugin slots. Add a button here
 * without moving that and the rail under-reserves, which pushes a plugin icon off the bottom of the
 * window - visible only on a crowded sidebar in focus mode.
 */
class HostActionsContentTest {
    @get:Rule
    val rule = createComposeRule()

    private fun mount(onOpenToolbox: () -> Unit = {}) {
        val item =
            SidebarItem(
                pluginContentId = PanelIds.PLUGIN_MANAGER,
                icon = Icons.Outlined.Extension,
                label = "Toolbox",
            )
        rule.setContent {
            Row {
                focusQuickActionsRail(
                    placement = FocusQuickActionsPlacement.RIGHT_RAIL,
                    onShowSettings = {},
                    toolbox = { hint, mod ->
                        ToolboxButton(item = item, onClick = onOpenToolbox, hintDirection = hint, modifier = mod)
                    },
                    onShowSearch = {},
                    onSignOut = {},
                ).forEach { action -> action() }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the toolbox is in the row`() {
        mount()
        rule.onNodeWithContentDescription("Toolbox").assertExists()
    }

    @Test
    fun `it sits directly after Settings`() {
        // Beside Settings, not merely present: both are "go and configure the app", where Search
        // and the tools launcher open something.
        mount()
        val settings = rule.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        val toolbox = rule.onNodeWithContentDescription("Toolbox").fetchSemanticsNode().boundsInRoot
        val search = rule.onNodeWithContentDescription("Search").fetchSemanticsNode().boundsInRoot

        assertTrue(toolbox.left >= settings.right, "Toolbox ($toolbox) must follow Settings ($settings)")
        assertTrue(toolbox.right <= search.left, "Toolbox ($toolbox) must precede Search ($search)")
    }

    @Test
    fun `clicking it opens the toolbox`() {
        var opened = 0
        mount(onOpenToolbox = { opened++ })
        rule.onNodeWithContentDescription("Toolbox").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `the reserve counts every button the row renders`() {
        // The invariant the rail's height arithmetic rests on. Asserted against the rendered list
        // rather than against a number repeated here, which could only agree with itself.
        val rendered =
            focusQuickActionsRail(
                placement = FocusQuickActionsPlacement.RIGHT_RAIL,
                onShowSettings = {},
                onShowSearch = {},
                onSignOut = {},
                toolbox = { hint, mod ->
                    ToolboxButton(
                        item =
                            SidebarItem(
                                pluginContentId = PanelIds.PLUGIN_MANAGER,
                                icon = Icons.Outlined.Extension,
                                label = "Toolbox",
                            ),
                        onClick = {},
                        hintDirection = hint,
                        modifier = mod,
                    )
                },
            )
        assertEquals(FOCUS_QUICK_ACTION_COUNT, rendered.size)
    }

    @Test
    fun `its own tag finds it`() {
        mount()
        rule.onNodeWithTag(ai.rever.boss.components.buttons.TOOLBOX_TAG).assertExists()
    }
}
