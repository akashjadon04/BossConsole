package ai.rever.boss.components.bars.horizontal

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the indicator's label, including the three degraded paths.
 *
 * Worth pinning because the two inputs genuinely can be absent at runtime and each has a
 * different "unknown" convention: the footprint is null when no platform reader could produce
 * one, while `SystemMemory` returns 0 rather than throwing. Rendering either as a zero would put
 * "0.0GB" in front of the user and read as a fact rather than a failure.
 */
class PerformanceIndicatorTextTest {
    private val gb = 1024f

    private fun text(
        activeBrowserMB: Float? = null,
        footprintMB: Float? = 2.1f * gb,
        systemUsedMB: Float? = 70f * gb,
        systemTotalMB: Float? = 128f * gb,
        heapUsedMB: Float = 296f,
        heapMaxMB: Float = 2f * gb,
    ) = memoryIndicatorText(activeBrowserMB, footprintMB, systemUsedMB, systemTotalMB, heapUsedMB, heapMaxMB)

    // region the active browser figure

    @Test
    fun `the active tab is nested inside the total`() {
        assertEquals("2.4GB (1.6GB tab) · 70/128GB", text(activeBrowserMB = 1.6f * gb, footprintMB = 2.4f * gb))
    }

    /**
     * Brackets, not a third peer, because the renderer is one of the processes already summed
     * into the footprint. Listed side by side, the two invite being added together.
     */
    @Test
    fun `no browser reading leaves today's string untouched`() {
        assertEquals("2.1GB · 70/128GB", text(activeBrowserMB = null))
    }

    /**
     * A tab figure with no total to sit inside has no referent, so it is dropped rather than
     * promoted to the front of the label.
     */
    @Test
    fun `the tab figure is suppressed when the footprint is unknown`() {
        assertEquals("70/128GB", text(activeBrowserMB = 1.6f * gb, footprintMB = null))
    }

    @Test
    fun `the tab figure survives an unreadable machine`() {
        assertEquals(
            "2.4GB (1.6GB tab)",
            text(activeBrowserMB = 1.6f * gb, footprintMB = 2.4f * gb, systemUsedMB = null, systemTotalMB = null),
        )
    }

    @Test
    fun `a small tab keeps its own unit rather than borrowing the total's`() {
        // Unlike the machine pair, these two are not a ratio and are formatted independently.
        assertEquals("2.4GB (180MB tab) · 70/128GB", text(activeBrowserMB = 180f, footprintMB = 2.4f * gb))
    }

    // endregion

    @Test
    fun `both readings show BOSS then the machine`() {
        assertEquals("2.1GB · 70/128GB", text())
    }

    @Test
    fun `a small machine keeps a decimal on both halves`() {
        assertEquals("512MB · 3.2/8.0GB", text(footprintMB = 512f, systemUsedMB = 3.2f * gb, systemTotalMB = 8f * gb))
    }

    /**
     * The pair is scaled by the total, never each half separately. Formatted independently this
     * would read "900MB/128GB", where the two numbers look comparable and are not.
     */
    @Test
    fun `a sub-gigabyte used figure still scales to the total's unit`() {
        assertEquals("2.1GB · 0.9/128GB", text(systemUsedMB = 900f))
    }

    @Test
    fun `an unreadable footprint leaves the machine pair`() {
        assertEquals("70/128GB", text(footprintMB = null))
    }

    @Test
    fun `an unreadable machine leaves the footprint`() {
        assertEquals("2.1GB", text(systemUsedMB = null, systemTotalMB = null))
    }

    @Test
    fun `losing both falls back to the heap ratio`() {
        assertEquals("296MB/2.0GB", text(footprintMB = null, systemUsedMB = null, systemTotalMB = null))
    }

    @Test
    fun `a half-known machine reading is not rendered`() {
        // Total without available, or the reverse, is not a pair - it must not print "70/0GB".
        assertEquals("2.1GB", text(systemUsedMB = 70f * gb, systemTotalMB = null))
        assertEquals("2.1GB", text(systemUsedMB = null, systemTotalMB = 128f * gb))
    }
}
