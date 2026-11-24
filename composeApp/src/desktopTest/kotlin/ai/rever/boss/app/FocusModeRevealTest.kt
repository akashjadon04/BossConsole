package ai.rever.boss.app

import ai.rever.boss.focusmode.FocusModeEdge
import ai.rever.boss.focusmode.FocusModeSettings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-edge switches, exercised through the composable that actually drives the
 * chrome rather than through the settings object alone.
 *
 * `FocusModeSettingsTest` proves what the Windows defaults *say*; this proves the
 * bars follow them - the sidebars stay up on Windows, and no hover strip is laid
 * over an edge that was never hidden. Neither could be seen from the data class,
 * because `rememberFocusModeReveal` writes `show*` from four `LaunchedEffect`s
 * whose keys are the ones this change rewrote.
 */
class FocusModeRevealTest {
    @get:Rule
    val rule = createComposeRule()

    /**
     * Mount the reveal for [settings] and settle it, past the 2s hide grace period.
     *
     * Note what this does and does not prove: `shown` starts false, so a hidden edge
     * reads false whether or not the effects ran. The load-bearing assertions are the
     * `assertTrue` ones - an edge focus mode does not clear has to be actively turned
     * back on by [EdgeRevealEffects]. The hover path is covered separately below.
     */
    private fun revealFor(settings: FocusModeSettings): FocusModeRevealState {
        lateinit var state: FocusModeRevealState
        rule.setContent {
            state = rememberFocusModeReveal(settings)
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(GRACE_PERIOD_MS)
        rule.waitForIdle()
        return state
    }

    @Test
    fun `windows defaults keep both sidebars while clearing top and bottom`() {
        val state = revealFor(FocusModeSettings.defaultsFor("Windows 11").copy(enabled = true))

        assertTrue(state.showLeftSidebar, "left sidebar must stay visible on Windows")
        assertTrue(state.showRightSidebar, "right sidebar must stay visible on Windows")
        assertFalse(state.showTopBar, "focus mode must still clear the top bar on Windows")
        assertFalse(state.showBottomBar, "focus mode must still clear the bottom bar on Windows")
    }

    @Test
    fun `other platforms clear all four`() {
        val state = revealFor(FocusModeSettings.defaultsFor("Mac OS X").copy(enabled = true))

        assertFalse(state.showTopBar)
        assertFalse(state.showLeftSidebar)
        assertFalse(state.showRightSidebar)
        assertFalse(state.showBottomBar)
    }

    @Test
    fun `focus mode off shows everything`() {
        val state = revealFor(FocusModeSettings.defaultsFor("Mac OS X").copy(enabled = false))

        assertTrue(state.showTopBar)
        assertTrue(state.showLeftSidebar)
        assertTrue(state.showRightSidebar)
        assertTrue(state.showBottomBar)
    }

    /** Turning an edge's switch off mid-session brings that bar back, and only it. */
    @Test
    fun `switching an edge off returns its bar`() {
        var settings by mutableStateOf(FocusModeSettings.defaultsFor("Linux").copy(enabled = true))
        lateinit var state: FocusModeRevealState
        rule.setContent { state = rememberFocusModeReveal(settings) }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(GRACE_PERIOD_MS)
        rule.waitForIdle()
        assertFalse(state.showLeftSidebar, "precondition: Linux starts by clearing the left sidebar")

        settings = settings.copy(hideLeftSidebar = false)
        rule.waitForIdle()

        assertTrue(state.showLeftSidebar)
        assertFalse(state.showRightSidebar, "the other edges are untouched")
        assertFalse(state.showTopBar)
    }

    /**
     * A strip is an invisible band the width of the reveal offset. Laying one over an
     * edge that is never hidden would put dead space over live content - on Windows,
     * over both sidebars, which is where the plugin panels are.
     *
     * Auto-reveal is turned on explicitly here: a Windows install has it off by
     * default, which is a separate switch and the subject of the next test.
     */
    @Test
    fun `strips exist only for edges that are actually hidden`() {
        val settings =
            FocusModeSettings
                .defaultsFor("Windows 11")
                .copy(enabled = true, autoRevealEnabled = true)
        rule.setContent {
            val state = rememberFocusModeReveal(settings)
            Box(modifier = Modifier.fillMaxSize()) {
                FocusModeHoverStrips(
                    state = state,
                    settings = settings,
                    revealOffsetDp = 30.dp,
                    barVisible = { true },
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(GRACE_PERIOD_MS)
        rule.waitForIdle()

        rule.onNodeWithTag(focusStripTag(FocusModeEdge.TOP)).assertExists()
        rule.onNodeWithTag(focusStripTag(FocusModeEdge.BOTTOM)).assertExists()
        rule.onNodeWithTag(focusStripTag(FocusModeEdge.LEFT)).assertDoesNotExist()
        rule.onNodeWithTag(focusStripTag(FocusModeEdge.RIGHT)).assertDoesNotExist()
    }

    /**
     * A bar switched off in `WindowAppearanceSettings` gets no strip either.
     *
     * Same rule as the test above, applied to the other way a bar can be absent: hover cannot bring
     * such a bar back (the scaffold requires the preference AND the reveal flag), so the strip would
     * be a dead 30dp band over live content. At the top edge it is worse than dead - sweeping it
     * flips `shown` to true, which takes the quick-actions cluster away without putting the bar back.
     */
    @Test
    fun `no strip for an edge whose bar is switched off in settings`() {
        val settings =
            FocusModeSettings
                .defaultsFor("Windows 11")
                .copy(enabled = true, autoRevealEnabled = true)
        rule.setContent {
            val state = rememberFocusModeReveal(settings)
            Box(modifier = Modifier.fillMaxSize()) {
                FocusModeHoverStrips(
                    state = state,
                    settings = settings,
                    revealOffsetDp = 30.dp,
                    barVisible = { edge -> edge != FocusModeEdge.TOP },
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(GRACE_PERIOD_MS)
        rule.waitForIdle()

        // TOP is cleared by focus mode on these defaults, so it would have had a strip.
        rule.onNodeWithTag(focusStripTag(FocusModeEdge.TOP)).assertDoesNotExist()
        rule.onNodeWithTag(focusStripTag(FocusModeEdge.BOTTOM)).assertExists()
    }

    /**
     * With auto-reveal off there is nothing to hover, so no strip is laid out at all.
     *
     * This is the state a stock Windows install is in - `defaultAutoReveal` is false
     * there - so on Windows focus mode clears the top and bottom bars and lays down
     * no strips at all. Toggling focus mode back off is what returns those two bars,
     * and the sidebars never left.
     */
    @Test
    fun `no strips at all when auto-reveal is off`() {
        val settings = FocusModeSettings.defaultsFor("Windows 11").copy(enabled = true)
        assertFalse(settings.autoRevealEnabled, "precondition: Windows starts with hover-reveal off")
        rule.setContent {
            val state = rememberFocusModeReveal(settings)
            Box(modifier = Modifier.fillMaxSize()) {
                FocusModeHoverStrips(
                    state = state,
                    settings = settings,
                    revealOffsetDp = 30.dp,
                    barVisible = { true },
                )
            }
        }
        rule.waitForIdle()

        for (edge in FocusModeEdge.entries) {
            rule.onNodeWithTag(focusStripTag(edge)).assertDoesNotExist()
        }
    }

    /**
     * The path this refactor actually rewrote: strip hover -> reveal delay -> shown, then
     * pointer out -> grace period -> hidden again. Four copy-pasted effect pairs became one
     * parameterised composable, and nothing else here drives that sequence.
     */
    @Test
    fun `hovering a strip reveals its bar and leaving hides it again`() {
        val settings = FocusModeSettings.defaultsFor("Mac OS X").copy(enabled = true)
        val state = revealFor(settings)
        assertFalse(state.showLeftSidebar, "precondition: focus mode cleared it")

        state[FocusModeEdge.LEFT].hoveringStrip = true
        rule.mainClock.advanceTimeBy(settings.revealDelayMs + FRAME_MS)
        rule.waitForIdle()

        assertTrue(state.showLeftSidebar, "hovering the strip past the reveal delay must show the bar")
        assertFalse(state.showRightSidebar, "only the hovered edge reveals")

        state[FocusModeEdge.LEFT].hoveringStrip = false
        rule.mainClock.advanceTimeBy(GRACE_PERIOD_MS)
        rule.waitForIdle()

        assertFalse(state.showLeftSidebar, "leaving the strip must hide it again after the grace period")
    }

    /** The delay is a real wait, not a formality: the bar must not appear before it elapses. */
    @Test
    fun `the bar does not appear before the reveal delay elapses`() {
        val settings = FocusModeSettings.defaultsFor("Mac OS X").copy(enabled = true, revealDelayMs = 500L)
        val state = revealFor(settings)

        state[FocusModeEdge.TOP].hoveringStrip = true
        rule.mainClock.advanceTimeBy(settings.revealDelayMs / 2)
        rule.waitForIdle()

        assertFalse(state.showTopBar, "revealed early, so the delay is not being honoured")

        rule.mainClock.advanceTimeBy(settings.revealDelayMs)
        rule.waitForIdle()
        assertTrue(state.showTopBar)
    }

    /** Tags are derived, so a renamed edge cannot silently detach a test from its strip. */
    @Test
    fun `every edge has its own strip tag`() {
        val tags = FocusModeEdge.entries.map { focusStripTag(it) }
        assertEquals(tags.size, tags.toSet().size, "duplicate strip tags: $tags")
        assertEquals("focus-strip-left", focusStripTag(FocusModeEdge.LEFT))
    }

    private companion object {
        /** Matches the hide delay in `rememberFocusModeReveal`, plus a frame. */
        const val GRACE_PERIOD_MS = 2_500L

        /** One frame past a deadline, so the effect has resumed rather than being mid-delay. */
        const val FRAME_MS = 100L
    }
}
