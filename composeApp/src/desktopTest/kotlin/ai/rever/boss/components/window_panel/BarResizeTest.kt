package ai.rever.boss.components.window_panel

import ai.rever.boss.components.window_panel.components.main_window_panels.clampBarWidth
import ai.rever.boss.window.TabBarVerticalWidthRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the arithmetic behind dragging the vertical bar's edge.
 *
 * The handle recomputes the width from where the drag STARTED plus the total travel, rather than
 * adding each delta to the current width. Those are the same thing until the width clamps, and
 * then they are not: an accumulator keeps taking deltas the width cannot follow, so dragging back
 * does nothing until the pointer returns past wherever it stopped tracking. That is a bug you can
 * only feel, never see in a screenshot, which is why the sums are pinned here.
 */
class BarResizeTest {
    private val min = TabBarVerticalWidthRange.start
    private val max = TabBarVerticalWidthRange.endInclusive

    /** What the handle computes: start width plus total travel, clamped. */
    private fun widthAfter(
        start: Float,
        travel: Float,
    ) = clampBarWidth(start + travel)

    @Test
    fun `the width follows the pointer`() {
        assertEquals(240f, widthAfter(200f, 40f))
        assertEquals(160f, widthAfter(200f, -40f))
        assertEquals(200f, widthAfter(200f, 0f))
    }

    @Test
    fun `it stops at both ends of the allowed range`() {
        assertEquals(max, widthAfter(300f, 500f))
        assertEquals(min, widthAfter(140f, -500f))
    }

    @Test
    fun `the width depends on total travel and nothing else`() {
        // The whole reason the handle measures from the drag's start. Having been clamped in the
        // middle of a gesture must leave no trace: once the pointer is back at a travel that maps
        // inside the range, the width is that value exactly. An accumulator would have gone on
        // adding deltas while pinned at the clamp, and the width would come back short by
        // however far past the end the pointer had gone.
        assertEquals(max, widthAfter(300f, 500f), "precondition: the middle of the drag clamps")
        assertEquals(290f, widthAfter(300f, -10f), "back inside the range, at the exact width")

        // Same total travel reached two different ways is the same width.
        assertEquals(widthAfter(200f, 30f), widthAfter(200f, 80f - 50f))
    }

    @Test
    fun `a width already inside the range is untouched`() {
        listOf(min, min + 1f, 200f, max - 1f, max).forEach {
            assertEquals(it, clampBarWidth(it), "clamping must not move a legal width")
        }
    }

    @Test
    fun `the range is the one the settings slider uses`() {
        // Two controls for one number. If they clamped differently, the slider and the drag would
        // disagree about how narrow the bar can be, and the bar would jump on the next drag.
        assertTrue(min > 0f && max > min, "range must be sane: $min..$max")
        assertEquals(min, clampBarWidth(0f))
        assertEquals(max, clampBarWidth(10_000f))
    }
}
