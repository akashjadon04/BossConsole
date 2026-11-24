package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.SidebarItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins what the tools dialog's search matches.
 *
 * The dialog is the only way to reach a tool when both icon strips are off, so a filter that is
 * too strict is not a cosmetic annoyance: it is a tool the user can see listed one keystroke
 * earlier and cannot find now.
 */
class ToolLauncherQueryTest {
    private fun item(
        id: String,
        label: String,
    ) = SidebarItem(
        pluginContentId = PanelId(panelId = id, defaultOrder = 0, pluginId = "test"),
        icon = Icons.Outlined.Extension,
        label = label,
    )

    private val terminal = item(id = "terminal-tab", label = "Terminal")

    @Test
    fun `an empty query matches everything`() {
        // The dialog opens as a full list rather than an empty state waiting to be typed into.
        assertTrue(matchesToolQuery(terminal, ""))
        assertTrue(matchesToolQuery(terminal, "   "), "whitespace is not a query either")
    }

    @Test
    fun `it matches the label`() {
        assertTrue(matchesToolQuery(terminal, "Term"))
        assertTrue(matchesToolQuery(terminal, "term"), "case must not matter")
        assertTrue(matchesToolQuery(terminal, "MINA"), "substring, not prefix")
    }

    @Test
    fun `it matches the id too`() {
        // The label is what the tooltip says; the id is what a plugin's own documentation and the
        // Toolbox call it. Someone searching either should find it.
        assertTrue(matchesToolQuery(terminal, "terminal-tab"))
        assertTrue(matchesToolQuery(item(id = "boss-notes", label = "Notes"), "boss-"))
    }

    @Test
    fun `it does not match something else`() {
        assertFalse(matchesToolQuery(terminal, "editor"))
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        // Typing into a search field picks up trailing spaces constantly.
        assertTrue(matchesToolQuery(terminal, "  Terminal  "))
    }
}
