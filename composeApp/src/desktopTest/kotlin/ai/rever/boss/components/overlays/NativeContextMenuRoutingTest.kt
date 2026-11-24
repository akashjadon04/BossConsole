package ai.rever.boss.components.overlays

import ai.rever.boss.plugin.ui.menu.NativeMenuNode
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The host-side half of the native menu decision: which menus are eligible, and how the app's
 * richer [ContextMenuItem] collapses into the native model.
 *
 * Rendering itself is not reachable here - `java.awt.MenuComponent` throws `HeadlessException` -
 * so the engine's own behaviour is covered in plugin-ui-core's `NativeContextMenusTest`.
 */
class NativeContextMenuRoutingTest {
    // ----- eligibility -----

    @Test
    fun `a plain menu is representable`() {
        val items =
            listOf(
                ContextMenuItem(text = "Split Right"),
                ContextMenuItem(isDivider = true),
                ContextMenuItem(text = "Close Tab"),
            )
        assertTrue(items.isNativeRepresentable())
    }

    @Test
    fun `an inline trailing button makes a menu non-representable`() {
        // The run-history and sidebar menus use these for inline delete and edit. Dropping them
        // silently would remove an affordance rather than merely restyle it.
        val items =
            listOf(
                ContextMenuItem(
                    text = "npm run dev",
                    trailingIcon = Icons.Filled.Delete,
                    onTrailingClick = {},
                ),
            )
        assertFalse(items.isNativeRepresentable())
    }

    @Test
    fun `a second trailing button also disqualifies`() {
        val items =
            listOf(
                ContextMenuItem(text = "entry", secondaryTrailingIcon = Icons.Filled.Delete),
            )
        assertFalse(items.isNativeRepresentable())
    }

    @Test
    fun `a leading icon does NOT disqualify`() {
        // Nearly every menu item in the app has one, so treating icons as blocking would leave
        // essentially every menu on the drawn path. An icon is decoration; losing it costs
        // appearance, not function.
        assertTrue(listOf(ContextMenuItem(text = "Open", icon = Icons.Filled.Delete)).isNativeRepresentable())
    }

    @Test
    fun `a realistic tab menu is representable`() {
        // Mirrors BossMainWindowPanel's tab menu, which is icon-per-item throughout. This is the
        // menu the feature is most visible on, so pin that it actually qualifies.
        val items =
            listOf(
                ContextMenuItem("Split Right", Icons.Filled.Delete, onClick = {}),
                ContextMenuItem(isDivider = true),
                ContextMenuItem("Close Tab", Icons.Filled.Delete, onClick = {}),
            )
        assertTrue(items.isNativeRepresentable())
    }

    @Test
    fun `a trailing button nested in a submenu still disqualifies`() {
        val nested =
            listOf(
                ContextMenuItem(
                    text = "Options",
                    subMenu =
                        listOf(
                            ContextMenuItem(text = "Entry", trailingIcon = Icons.Filled.Delete),
                        ),
                ),
            )
        assertFalse(nested.isNativeRepresentable())
    }

    @Test
    fun `a submenu is inspected too, at any depth`() {
        val nested =
            listOf(
                ContextMenuItem(
                    text = "Options",
                    subMenu =
                        listOf(
                            ContextMenuItem(
                                text = "More",
                                subMenu = listOf(ContextMenuItem(text = "Deep", trailingIcon = Icons.Filled.Delete)),
                            ),
                        ),
                ),
            )
        assertFalse(nested.isNativeRepresentable(), "a trailing button two levels down still counts")

        val clean =
            listOf(
                ContextMenuItem(text = "Options", subMenu = listOf(ContextMenuItem(text = "Save"))),
            )
        assertTrue(clean.isNativeRepresentable())
    }

    @Test
    fun `an empty menu is trivially representable`() {
        assertTrue(emptyList<ContextMenuItem>().isNativeRepresentable())
    }

    // ----- icons -----

    @Test
    fun `distinct icons are collected once, at any depth`() {
        val a = Icons.Filled.Delete
        val items =
            listOf(
                ContextMenuItem(text = "one", icon = a),
                ContextMenuItem(text = "two", icon = a),
                ContextMenuItem(
                    text = "sub",
                    subMenu = listOf(ContextMenuItem(text = "deep", icon = a)),
                ),
                ContextMenuItem(text = "none"),
            )
        // Same ImageVector used three times, rasterised once.
        assertEquals(listOf(a), items.collectIcons())
    }

    @Test
    fun `an item with no icon collects nothing`() {
        assertTrue(listOf(ContextMenuItem(text = "plain")).collectIcons().isEmpty())
    }

    @Test
    fun `a rasterised icon is carried onto the native node, including in submenus`() {
        val vector = Icons.Filled.Delete
        val bitmap = ImageBitmap(4, 4)
        val nodes =
            listOf(
                ContextMenuItem(text = "top", icon = vector),
                ContextMenuItem(
                    text = "sub",
                    subMenu = listOf(ContextMenuItem(text = "deep", icon = vector)),
                ),
            ).toNativeMenuNodes(mapOf(vector to bitmap))

        assertEquals(bitmap, (nodes[0] as NativeMenuNode.Item).icon)
        val submenu = nodes[1] as NativeMenuNode.Submenu
        assertEquals(bitmap, (submenu.children.single() as NativeMenuNode.Item).icon)
    }

    @Test
    fun `an unrasterised icon degrades to no icon rather than failing`() {
        // The map is empty when rasterisation was skipped; the item must still render.
        val nodes =
            listOf(ContextMenuItem(text = "top", icon = Icons.Filled.Delete)).toNativeMenuNodes()
        assertEquals(null, (nodes.single() as NativeMenuNode.Item).icon)
        assertEquals("top", (nodes.single() as NativeMenuNode.Item).label)
    }

    // ----- conversion -----

    @Test
    fun `dividers become separators and items keep their label`() {
        val nodes =
            listOf(
                ContextMenuItem(text = "New Tab"),
                ContextMenuItem(isDivider = true),
                ContextMenuItem(text = "Close"),
            ).toNativeMenuNodes()

        assertEquals(3, nodes.size)
        assertEquals("New Tab", (nodes[0] as NativeMenuNode.Item).label)
        assertEquals(NativeMenuNode.Separator, nodes[1])
        assertEquals("Close", (nodes[2] as NativeMenuNode.Item).label)
    }

    @Test
    fun `submenus convert recursively`() {
        val nodes =
            listOf(
                ContextMenuItem(
                    text = "Share",
                    subMenu = listOf(ContextMenuItem(text = "Tab"), ContextMenuItem(text = "Window")),
                ),
            ).toNativeMenuNodes()

        val submenu = nodes.single() as NativeMenuNode.Submenu
        assertEquals("Share", submenu.label)
        assertEquals(listOf("Tab", "Window"), submenu.children.map { (it as NativeMenuNode.Item).label })
    }

    @Test
    fun `the click handler is carried across, not dropped`() {
        var fired = 0
        val nodes = listOf(ContextMenuItem(text = "Go", onClick = { fired += 1 })).toNativeMenuNodes()
        (nodes.single() as NativeMenuNode.Item).action()
        assertEquals(1, fired)
    }

    @Test
    fun `a divider that also carries text is still a separator`() {
        // isDivider wins: the drawn renderer ignores text on a divider row too.
        val nodes = listOf(ContextMenuItem(text = "ignored", isDivider = true)).toNativeMenuNodes()
        assertEquals(NativeMenuNode.Separator, nodes.single())
    }
}
