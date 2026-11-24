package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Guards the renderer-pid lifecycle.
 *
 * This is the part of the per-tab memory figure that can be wrong *silently*. Neither
 * `Frame.renderProcess()` nor `RenderProcess.pid()` carries a `checkNotClosed`, so a stale read
 * answers with the previous document's renderer instead of throwing, and Chromium recycles pids,
 * so a value kept past its renderer's death can name an unrelated helper. Both give a plausible
 * number charged to the wrong tab, which no amount of downstream testing would catch.
 *
 * It lived as a `@Volatile` field on `BrowserHandleImpl` and was therefore untestable without a
 * live browser and a real navigation - which is why it is a holder now.
 */
class RendererPidTest {
    @Test
    fun `unknown until a document commits`() {
        assertNull(RendererPid().value)
    }

    @Test
    fun `a commit records the renderer`() {
        val pid = RendererPid()
        pid.onCommit(2417)
        assertEquals(2417, pid.value)
    }

    /**
     * The about:blank case. Injection is skipped for it, so before the capture moved out of that
     * gate a tab navigating from a heavy site to the dashboard kept reporting the old renderer.
     * Overwriting with unknown is what makes the figure disappear instead of lying.
     */
    @Test
    fun `a commit that could not read a pid clears the previous one`() {
        val pid = RendererPid()
        pid.onCommit(2417)
        pid.onCommit(null)
        assertNull(pid.value)
    }

    @Test
    fun `a renderer going away clears it`() {
        val pid = RendererPid()
        pid.onCommit(2417)
        pid.onGone()
        assertNull(pid.value)
    }

    /**
     * Clearing must not be terminal. A crashed renderer is replaced and the reload commits a new
     * one; a holder that latched at unknown would leave that tab permanently unreported.
     */
    @Test
    fun `a commit after a death records the new renderer`() {
        val pid = RendererPid()
        pid.onCommit(2417)
        pid.onGone()
        pid.onCommit(9001)
        assertEquals(9001, pid.value)
    }

    @Test
    fun `a cross-site swap replaces rather than accumulates`() {
        // No event fires for an ordinary swap, so the following commit is the only thing that
        // moves the pid off the previous renderer.
        val pid = RendererPid()
        pid.onCommit(2417)
        pid.onCommit(2418)
        assertEquals(2418, pid.value)
    }
}
