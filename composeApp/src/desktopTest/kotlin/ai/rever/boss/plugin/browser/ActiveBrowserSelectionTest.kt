package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [selectActiveHandleId] — the tie-break that decides which browser the View menu's
 * Zoom In / Zoom Out / Actual Size / Reload act on.
 *
 * Pure by construction: the selector takes [ActiveBrowserRegistry.Entry] values rather than
 * `BrowserHandle`s precisely so the interesting logic can be tested without a JxBrowser browser.
 */
class ActiveBrowserSelectionTest {
    private fun entry(
        handleId: String,
        windowId: String = "w1",
        inMainPanel: Boolean = true,
        panelActive: Boolean = true,
        sequence: Long = 1,
    ) = ActiveBrowserRegistry.Entry(handleId, windowId, inMainPanel, panelActive, sequence)

    @Test
    fun `no candidates yields no browser`() {
        assertNull(selectActiveHandleId(emptyList(), "w1"))
    }

    @Test
    fun `a browser in another window is never chosen`() {
        val candidates = listOf(entry("other", windowId = "w2", sequence = 99))
        assertNull(selectActiveHandleId(candidates, "w1"))
        assertEquals("other", selectActiveHandleId(candidates, "w2"))
    }

    @Test
    fun `in a split, the active panel wins regardless of which registered last`() {
        // Both surfaces re-register in the same frame when the active panel changes, and the order
        // between them is not something the effects control. panelActive outranking sequence is
        // what makes that race irrelevant, so assert it under BOTH orderings.
        val activeFirst =
            listOf(
                entry("active", panelActive = true, sequence = 1),
                entry("inactive", panelActive = false, sequence = 2),
            )
        assertEquals("active", selectActiveHandleId(activeFirst, "w1"))

        val activeLast =
            listOf(
                entry("inactive", panelActive = false, sequence = 1),
                entry("active", panelActive = true, sequence = 2),
            )
        assertEquals("active", selectActiveHandleId(activeLast, "w1"))
    }

    @Test
    fun `a sidebar browser never beats one in the main content area`() {
        // LocalIsPanelActive DEFAULTS TO TRUE, so a surface rendered outside a managed panel - a
        // sidebar slot, a dialog, a test host - reports panelActive = true as well. Only
        // inMainPanel separates them, which is why it has to outrank both other keys: here the
        // sidebar entry wins on panelActive and on sequence and must still lose.
        val candidates =
            listOf(
                entry("sidebar", inMainPanel = false, panelActive = true, sequence = 99),
                entry("main", inMainPanel = true, panelActive = false, sequence = 1),
            )
        assertEquals("main", selectActiveHandleId(candidates, "w1"))
    }

    @Test
    fun `otherwise the most recently shown browser wins`() {
        val candidates =
            listOf(
                entry("older", sequence = 1),
                entry("newer", sequence = 2),
            )
        assertEquals("newer", selectActiveHandleId(candidates, "w1"))
    }
}
