package ai.rever.boss.app

import ai.rever.boss.focusmode.FocusModeEdge
import ai.rever.boss.focusmode.FocusModeSettings
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [focusQuickActionsVisible], which was the highest-consequence line in this feature with no
 * test behind it.
 *
 * The `hides(TOP)` half looks redundant next to `!showTopBar` and is not. `showTopBar` is written
 * from a `LaunchedEffect`, so it reads false on the first composition of every window regardless of
 * whether focus mode is enabled - and dropping the conjunct is a native always-on-top window
 * created and disposed on every window open, a corner flash for users who never enable focus mode,
 * and a content-pane read before the pane is showing. None of that is visible to any other test.
 */
class FocusModeVisibilityTest {
    private val focusOnHidingTop = FocusModeSettings(enabled = true, hideTopBar = true)

    /** Named so the appearance flag reads as what it is at each call: nothing is hidden for good. */
    private fun visible(
        settings: FocusModeSettings,
        showTopBar: Boolean,
        topBarHidden: Boolean = false,
    ) = focusQuickActionsVisible(settings, topBarHidden = topBarHidden, showTopBar = showTopBar)

    @Test
    fun `hidden once focus mode has cleared the top bar`() {
        assertTrue(visible(focusOnHidingTop, showTopBar = false))
    }

    @Test
    fun `stands down while the top bar is revealed`() {
        assertFalse(visible(focusOnHidingTop, showTopBar = true))
    }

    @Test
    fun `never composed when focus mode is off, whatever the reveal state says`() {
        // The launch path: showTopBar is false on the first composition of every window because the
        // effect that turns it back on has not run yet. This is the case the settings half exists
        // for, and the only one where the two conjuncts disagree.
        val off = focusOnHidingTop.copy(enabled = false)

        assertFalse(visible(off, showTopBar = false))
        assertFalse(visible(off, showTopBar = true))
    }

    @Test
    fun `never composed when focus mode is on but keeps the top bar`() {
        // Per-edge settings: focus mode enabled with hideTopBar off leaves the bar in place, so the
        // three actions were never taken away and the cluster has nothing to restore.
        val keepsTop = focusOnHidingTop.copy(hideTopBar = false)

        assertFalse(visible(keepsTop, showTopBar = false))
        assertFalse(visible(keepsTop, showTopBar = true))
    }

    @Test
    fun `composed when the top bar is switched off for good, at the showTopBar the app really has`() {
        // The default configuration, and the one this feature exists for: focus mode off, the user
        // picks "Hide Top Bar". Settings, Search and Sign Out live only in the top bar and the
        // native View menu carries Settings but neither of the other two, so if this answers false
        // Sign Out is unreachable.
        //
        // `showTopBar = true` is not a detail, it is the whole point. With focus mode off
        // EdgeRevealEffects sets `shown = true` unconditionally (`if (!hidden || ...)`), so true is
        // the ONLY value the scaffold can pass here. An earlier revision asserted this at
        // `showTopBar = false` - an unreachable input - and shipped the bug it was written to
        // prevent, which is what review caught on #187. See `steadyStateShowTopBar`.
        val noFocusMode = FocusModeSettings(enabled = false)

        assertTrue(visible(noFocusMode, showTopBar = true, topBarHidden = true))
    }

    @Test
    fun `a preference-hidden top bar keeps the cluster even while focus mode reveals that edge`() {
        // Focus mode clearing TOP *and* the preference hiding it: hovering the edge flips
        // `shown` to true, but the scaffold's conjunction still refuses to compose the bar. If this
        // answered false the user would sweep the edge to get chrome back and instead lose the only
        // chrome they had, for the hover plus the grace period.
        val focusAlsoClearsTop = FocusModeSettings(enabled = true, hideTopBar = true)

        assertTrue(visible(focusAlsoClearsTop, showTopBar = true, topBarHidden = true))
    }

    @Test
    fun `the four configurations, each at the showTopBar the scaffold would actually pass`() {
        // The gap that let the bug through: every other assertion picks its own `showTopBar`, so
        // none of them noticed that one combination was unreachable. This derives it the way
        // EdgeRevealEffects does in steady state, so the inputs are the app's own.
        //
        // Focus mode hidden + preference hidden are the two axes; the cluster is needed in exactly
        // the three cases where the bar is not on screen.
        val off = FocusModeSettings(enabled = false)
        val clearsTop = FocusModeSettings(enabled = true, hideTopBar = true)

        assertFalse(reachable(off, topBarHidden = false), "bar is up: it owns the three itself")
        assertTrue(reachable(off, topBarHidden = true), "hidden by preference, focus mode off")
        assertTrue(reachable(clearsTop, topBarHidden = false), "cleared by focus mode")
        assertTrue(reachable(clearsTop, topBarHidden = true), "hidden both ways")
    }

    /**
     * [focusQuickActionsVisible] at the `showTopBar` the scaffold would really pass for these
     * settings, rather than one the test picked.
     */
    private fun reachable(
        settings: FocusModeSettings,
        topBarHidden: Boolean,
    ) = focusQuickActionsVisible(
        settings = settings,
        topBarHidden = topBarHidden,
        showTopBar = steadyStateShowTopBar(settings),
    )

    /**
     * What `reveal.showTopBar` settles to for [settings], mirroring `EdgeRevealEffects`:
     * `shown` is set true whenever `!settings.hides(edge)`, and only an edge focus mode clears ever
     * runs the timer that can turn it off. Not hovering, so an edge focus mode clears reads false.
     */
    private fun steadyStateShowTopBar(settings: FocusModeSettings) = !settings.hides(FocusModeEdge.TOP)
}
