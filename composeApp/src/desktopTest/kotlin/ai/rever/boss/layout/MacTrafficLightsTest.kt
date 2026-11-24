package ai.rever.boss.layout

import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.WindowAppearanceSettings
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins which chrome keeps clear of the macOS traffic lights.
 *
 * The window sets `apple.awt.fullWindowContent`, so the buttons are drawn over the content. They
 * occupy a BOX - [TRAFFIC_LIGHT_WIDTH] by [TRAFFIC_LIGHT_HEIGHT] in the top-left corner - not a
 * band across the top, so the clearance goes on whatever is under that box: the top bar when one is
 * on, the update banner while one is up, and otherwise the left columns that fall inside it.
 *
 * **The columns are asked per-column, by offset.** They run strip, then an open plugin panel, then
 * the vertical tab bar, and only the first 78dp is under the lights - so which of them needs
 * clearing depends on what is open. One answer for "the columns" was right only while the bar was
 * second; a panel open in front of it put the lights on the panel's header.
 *
 * **There is no title-row fallback.** It used to be drawn whenever the columns came to less than
 * the box, which made "Show Title Bar = off" untrue on macOS as soon as the tab bar was collapsed,
 * and made a 26dp row appear and vanish mid-drag as a window was resized past the bar's
 * auto-collapse width.
 *
 * Every wrong answer here is a visible defect, and all of them are only visible on macOS, which is
 * not where most of this is developed.
 */
class MacTrafficLightsTest {
    private val bare =
        WindowAppearanceSettings(
            showTitleBar = false,
            showTopBar = false,
            showLeftStrip = false,
            tabBarPosition = TabBarPosition.LEFT,
        )

    @Test
    fun `no inset off macOS`() {
        // Elsewhere the title row is an ordinary bar above the content, not an overlay on it.
        assertEquals(TrafficLightInset.NONE, macTrafficLightInset(bare, isMacOs = false))
    }

    @Test
    fun `no inset while the title row is on`() {
        // The row is exactly what holds them.
        assertEquals(
            TrafficLightInset.NONE,
            macTrafficLightInset(bare.copy(showTitleBar = true), isMacOs = true),
        )
    }

    @Test
    fun `the top bar takes it whenever it is on`() {
        // Asked before the columns: the bar spans the full width at y=0, so it is what is under
        // the box and the columns start below it.
        val withBar = bare.copy(showTopBar = true)
        assertEquals(TrafficLightInset.TOP_BAR, macTrafficLightInset(withBar, isMacOs = true))
        assertEquals(TRAFFIC_LIGHT_WIDTH, TrafficLightInset.TOP_BAR.barStartInset())

        val everything = withBar.copy(showLeftStrip = true)
        assertEquals(TrafficLightInset.TOP_BAR, macTrafficLightInset(everything, isMacOs = true))
    }

    @Test
    fun `the columns take it when they are wide enough`() {
        // A full-width tab bar is 200dp on its own, and a strip beside it only adds to that.
        assertEquals(TrafficLightInset.LEFT_COLUMNS, macTrafficLightInset(bare, isMacOs = true))
        assertEquals(
            TrafficLightInset.LEFT_COLUMNS,
            macTrafficLightInset(bare.copy(showLeftStrip = true), isMacOs = true),
        )
    }

    @Test
    fun `a strip beside a collapsed rail is measured at the density's width`() {
        // 36dp floor vs the 40dp Comfortable actually draws: 72dp falls back, 80dp fits.
        val stripAndRail = bare.copy(showLeftStrip = true)
        assertEquals(
            TrafficLightInset.CONTENT,
            macTrafficLightInset(stripAndRail, isMacOs = true, barCollapsed = true),
            "the floor, which is what the default measures",
        )
        assertEquals(
            TrafficLightInset.LEFT_COLUMNS,
            macTrafficLightInset(stripAndRail, isMacOs = true, barCollapsed = true, stripWidth = 40.dp),
            "Comfortable, which is what ships",
        )
    }

    @Test
    fun `chrome too narrow to hold the box keeps the title row`() {
        // The row is a real cost - full width, to protect one corner - and it was removed once on
        // the reasoning that "Show Title Bar = off" ought to mean off. That was wrong: with no
        // column wide enough, the buttons land on the PANE, where they sit over the active pane's
        // focus outline and nothing can be moved out from under them. Indenting the pane's tab
        // strip moves the chips and leaves the outline broken behind the buttons.
        val stripOnly = bare.copy(showLeftStrip = true, tabBarPosition = TabBarPosition.TOP)
        val answer = macTrafficLightInset(stripOnly, isMacOs = true)

        assertEquals(TrafficLightInset.CONTENT, answer)
        assertTrue(answer.needsTitleRow(showTitleBar = false), "the row is what holds them here")
    }

    @Test
    fun `a collapsed rail alone keeps the title row`() {
        // The shipped default once the bar is collapsed: one 40dp rail, nothing else down the
        // left. Half the box would be over the pane, so the row stays.
        val collapsed = macTrafficLightInset(bare, isMacOs = true, barCollapsed = true)

        assertEquals(TrafficLightInset.CONTENT, collapsed)
        assertTrue(collapsed.needsTitleRow(showTitleBar = false))
    }

    @Test
    fun `an open plugin panel is a column, and a wide one`() {
        // What makes the row RARER without removing it: a panel is hundreds of dp, so a window
        // with a collapsed rail and a panel open carries the clearance in its columns.
        val withPanel = macTrafficLightInset(bare, isMacOs = true, barCollapsed = true, leftPanelOpen = true)

        assertEquals(TrafficLightInset.LEFT_COLUMNS, withPanel)
        assertFalse(withPanel.needsTitleRow(showTitleBar = false), "no row when a column can hold them")
    }

    @Test
    fun `a column inside the box is inset and one past it is not`() {
        val columns = TrafficLightInset.LEFT_COLUMNS

        assertEquals(TRAFFIC_LIGHT_HEIGHT, columns.columnInset(), "the strip, at offset zero")
        assertEquals(TRAFFIC_LIGHT_HEIGHT, columns.columnInset(40.dp), "a second column, still inside")
        assertEquals(0.dp, columns.columnInset(TRAFFIC_LIGHT_WIDTH), "exactly past the box")
        assertEquals(0.dp, columns.columnInset(300.dp), "well past it")
    }

    @Test
    fun `only the LEFT_COLUMNS answer insets a column at all`() {
        listOf(TrafficLightInset.NONE, TrafficLightInset.TOP_BAR, TrafficLightInset.BANNER).forEach {
            assertEquals(0.dp, it.columnInset(), "$it must not inset a column")
        }
    }

    @Test
    fun `an open plugin panel is the column under the lights, not the bar`() {
        // The screenshot bug: the panel sits between the strip and the tab bar, so with one open
        // the lights land on the PANEL's header - while the bar, out of reach behind it, was the
        // one keeping a 28dp gap.
        val open = leftColumnOffsets(showLeftStrip = true, leftPanelOpen = true, stripWidth = 40.dp)
        val columns = TrafficLightInset.LEFT_COLUMNS

        assertEquals(40.dp, open.panel, "the panel starts after the strip")
        assertEquals(TRAFFIC_LIGHT_HEIGHT, columns.columnInset(open.panel), "so the panel is inset")
        assertEquals(0.dp, columns.columnInset(open.bar), "and the bar behind it is not")
    }

    @Test
    fun `with no panel open the bar is second and takes it`() {
        val shut = leftColumnOffsets(showLeftStrip = true, leftPanelOpen = false, stripWidth = 40.dp)
        assertEquals(40.dp, shut.bar, "the bar follows the strip directly")
        assertEquals(TRAFFIC_LIGHT_HEIGHT, TrafficLightInset.LEFT_COLUMNS.columnInset(shut.bar))
    }

    @Test
    fun `with no strip the first column starts at the edge`() {
        val noStrip = leftColumnOffsets(showLeftStrip = false, leftPanelOpen = false, stripWidth = 40.dp)
        assertEquals(0.dp, noStrip.panel)
        assertEquals(0.dp, noStrip.bar)
        assertEquals(TRAFFIC_LIGHT_HEIGHT, TrafficLightInset.LEFT_COLUMNS.columnInset(noStrip.bar))
    }

    @Test
    fun `the banner takes the clearance off the top bar while it is up`() {
        val withBar = bare.copy(showTopBar = true)
        assertEquals(TrafficLightInset.TOP_BAR, macTrafficLightInset(withBar, isMacOs = true))
        assertEquals(
            TrafficLightInset.BANNER,
            macTrafficLightInset(withBar, isMacOs = true, bannerVisible = true),
        )
    }

    @Test
    fun `the banner takes the clearance off the columns while it is up`() {
        // Taking rather than adding: a banner that indented itself while the columns kept their
        // own inset opened an empty band under it, above the tab bar's Favorites shelf.
        val underBanner = macTrafficLightInset(bare, isMacOs = true, bannerVisible = true)

        assertEquals(TrafficLightInset.BANNER, underBanner)
        assertEquals(0.dp, underBanner.columnInset())
        assertEquals(TRAFFIC_LIGHT_WIDTH, underBanner.bannerStartInset())
    }

    @Test
    fun `a banner changes nothing where the title row holds the lights`() {
        // The row is drawn ABOVE the banner, so it goes on holding them.
        val titled = bare.copy(showTitleBar = true)
        val answer = macTrafficLightInset(titled, isMacOs = true, bannerVisible = true)

        assertEquals(TrafficLightInset.NONE, answer)
        assertEquals(0.dp, answer.bannerStartInset())
    }

    @Test
    fun `no banner inset off macOS`() {
        assertEquals(
            TrafficLightInset.NONE,
            macTrafficLightInset(bare, isMacOs = false, bannerVisible = true),
        )
    }

    @Test
    fun `each answer produces exactly one kind of inset`() {
        // The insets are different axes - a column takes height, the bar and the banner take width
        // - so an answer producing two, or none where one is needed, is a layout bug either way.
        assertEquals(TRAFFIC_LIGHT_HEIGHT, TrafficLightInset.LEFT_COLUMNS.columnInset())
        assertEquals(0.dp, TrafficLightInset.LEFT_COLUMNS.barStartInset())
        assertEquals(0.dp, TrafficLightInset.LEFT_COLUMNS.bannerStartInset())

        assertEquals(TRAFFIC_LIGHT_WIDTH, TrafficLightInset.TOP_BAR.barStartInset())
        assertEquals(0.dp, TrafficLightInset.TOP_BAR.columnInset())
        assertEquals(0.dp, TrafficLightInset.TOP_BAR.bannerStartInset())

        assertEquals(TRAFFIC_LIGHT_WIDTH, TrafficLightInset.BANNER.bannerStartInset())
        assertEquals(0.dp, TrafficLightInset.BANNER.columnInset())
        assertEquals(0.dp, TrafficLightInset.BANNER.barStartInset())

        assertEquals(0.dp, TrafficLightInset.NONE.columnInset())
        assertEquals(0.dp, TrafficLightInset.NONE.barStartInset())
        assertEquals(0.dp, TrafficLightInset.NONE.bannerStartInset())
    }

    @Test
    fun `only CONTENT draws a title row the user did not ask for`() {
        TrafficLightInset.entries.forEach {
            assertEquals(
                it == TrafficLightInset.CONTENT,
                it.needsTitleRow(showTitleBar = false),
                "$it drew the wrong answer with the setting off",
            )
            assertTrue(it.needsTitleRow(showTitleBar = true), "$it must still honour the setting")
        }
    }

    @Test
    fun `the shipped defaults inset the left columns`() {
        // No title row, no top bar, no strips, tabs down the left.
        assertEquals(
            TrafficLightInset.LEFT_COLUMNS,
            macTrafficLightInset(WindowAppearanceSettings(), isMacOs = true),
        )
    }
}
