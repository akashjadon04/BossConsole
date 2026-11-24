package ai.rever.boss.layout

import ai.rever.boss.app.ToolLauncherPlacement
import ai.rever.boss.app.toolLauncherPlacement
import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.window.TabBarPosition
import ai.rever.boss.window.WindowAppearanceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that the rules which place chrome read what is DRAWN, not what is preferred.
 *
 * A strip switched off in Settings and a strip focus mode has cleared are the same thing to a
 * layout: nothing is there. Reading the preference alone left the tools launcher out of a window
 * whose strips focus mode had both cleared - a window with no way to open a tool at all - and put
 * the macOS traffic-light clearance on a top bar that was not being drawn.
 */
class DrawnChromeTest {
    private val everything =
        WindowAppearanceSettings(
            showTitleBar = false,
            showTopBar = true,
            showLeftStrip = true,
            showRightStrip = true,
            tabBarPosition = TabBarPosition.LEFT,
        )

    private val focusOff = FocusModeSettings(enabled = false)

    /** Focus mode on, clearing every edge it can - the configuration this is about. */
    private val focusClearsAll =
        FocusModeSettings(
            enabled = true,
            hideTopBar = true,
            hideLeftSidebar = true,
            hideRightSidebar = true,
        )

    @Test
    fun `with focus mode off nothing changes`() {
        assertEquals(everything, everything.asDrawn(focusOff))
    }

    @Test
    fun `a cleared edge reads as switched off`() {
        val drawn = everything.asDrawn(focusClearsAll)

        assertTrue(!drawn.showTopBar && !drawn.showLeftStrip && !drawn.showRightStrip)
    }

    @Test
    fun `it never turns a bar back ON`() {
        // asDrawn only ever subtracts. A preference of "off" must stay off whatever focus mode is
        // doing, or switching focus mode on would hand somebody back a bar they had hidden.
        //
        // The status bar is named too: focus mode hides it by default, so leaving it at the
        // preference default would compare an all-off object against one that had lost it - which
        // is what this assertion caught the first time it ran.
        val allOff =
            everything.copy(
                showTopBar = false,
                showLeftStrip = false,
                showRightStrip = false,
                showBottomBar = false,
            )

        assertEquals(allOff, allOff.asDrawn(focusOff))
        assertEquals(allOff, allOff.asDrawn(focusClearsAll))
    }

    @Test
    fun `the tools launcher appears when focus mode clears both strips`() {
        // The case that made this necessary: both strips are preferred ON, so the launcher used to
        // decide it was not needed - while neither strip was on screen.
        val drawn = everything.asDrawn(focusClearsAll)

        assertEquals(
            ToolLauncherPlacement.HOST_ACTIONS,
            toolLauncherPlacement(
                leftStripHidden = !drawn.showLeftStrip,
                rightStripHidden = !drawn.showRightStrip,
            ),
        )
        assertEquals(
            ToolLauncherPlacement.NONE,
            toolLauncherPlacement(leftStripHidden = false, rightStripHidden = false),
            "and with both actually drawn it stays out of the way",
        )
    }

    @Test
    fun `the traffic lights stop reserving a top bar focus mode is not drawing`() {
        // Otherwise the clearance is an indent in a bar nobody can see, while the lights sit on
        // the columns that are actually up there.
        assertEquals(
            TrafficLightInset.TOP_BAR,
            macTrafficLightInset(everything, isMacOs = true),
        )
        assertEquals(
            TrafficLightInset.LEFT_COLUMNS,
            macTrafficLightInset(everything.asDrawn(focusClearsAll), isMacOs = true),
        )
    }
}
