package ai.rever.boss.components.home

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The home screen's two reflow decisions, as pure functions.
 *
 * Pure so they are pinned without a display, following `AuthScaffold.showsBrandPanel` and
 * `BossDialog.shouldRouteHeavyweight`. The screen this replaces made the same kind of decision
 * with a bare `if (maxWidth < HeaderCompactWidth)` inline in a composable, which nothing could
 * assert on.
 *
 * What these protect is the actual complaint: with 33 plugins installed, the old screen laid every
 * group out in a `Row(horizontalScroll(...))` of fixed-width cards, so most tools sat past the
 * right edge with no affordance saying they were there. A column count that silently collapsed to
 * 1, or never grew past 1, would reproduce that.
 */
class HomeLayoutTest {
    @Test
    fun `column count grows with available width`() {
        // One tile plus one gap per additional column: 132 + n * 140.
        assertEquals(1, homeToolColumns(132.dp, minTileWidth = 132.dp, gap = 8.dp))
        assertEquals(2, homeToolColumns(272.dp, minTileWidth = 132.dp, gap = 8.dp))
        assertEquals(3, homeToolColumns(412.dp, minTileWidth = 132.dp, gap = 8.dp))
        assertEquals(8, homeToolColumns(1112.dp, minTileWidth = 132.dp, gap = 8.dp))
    }

    @Test
    fun `a width one pixel short of the next column does not claim it`() {
        // Claiming a column that does not fit is how tiles end up clipped at the right edge -
        // the failure this whole grid replaces, just at a smaller scale.
        assertEquals(1, homeToolColumns(271.dp, minTileWidth = 132.dp, gap = 8.dp))
        assertEquals(2, homeToolColumns(411.dp, minTileWidth = 132.dp, gap = 8.dp))
    }

    @Test
    fun `a panel narrower than one tile still gets a column`() {
        // Returning 0 would divide by zero when chunking, and an empty grid is worse than a
        // squeezed one. A split panel really can be this narrow.
        assertEquals(1, homeToolColumns(40.dp, minTileWidth = 132.dp, gap = 8.dp))
        assertEquals(1, homeToolColumns(0.dp, minTileWidth = 132.dp, gap = 8.dp))
        assertEquals(1, homeToolColumns((-10).dp, minTileWidth = 132.dp, gap = 8.dp))
    }

    @Test
    fun `a realistic window shows several columns`() {
        // The point of the rewrite: a normal window shows many tools at once, not one strip.
        assertTrue(
            homeToolColumns(900.dp) >= 5,
            "A 900dp panel should show at least five tools per row, or the grid is no better " +
                "than the horizontal strip it replaced",
        )
    }

    @Test
    fun `the header search sits inline only when there is room for it`() {
        assertTrue(showsInlineSearch(HeaderInlineSearchMinWidth))
        assertTrue(showsInlineSearch(HeaderInlineSearchMinWidth + 1.dp))
        assertFalse(showsInlineSearch(HeaderInlineSearchMinWidth - 1.dp))
        assertFalse(showsInlineSearch(320.dp))
    }
}
