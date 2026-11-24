package ai.rever.boss.plugin.browser

/**
 * The pid of the Chromium renderer serving a browser's current main-frame document.
 *
 * A holder rather than a bare `@Volatile var` on the handle, because this tiny state machine is
 * the part of the per-tab memory figure that carries the actual hazard, and as a field on
 * `BrowserHandleImpl` it could not be tested without a live browser and a real navigation.
 *
 * The hazard is that **being wrong here is silent**. Of the JxBrowser chain
 * `mainFrame -> renderProcess -> pid`, neither of the last two carries a `checkNotClosed`, so a
 * `Frame` read after a navigation does not throw - it answers with the *previous* renderer's pid.
 * Chromium also recycles pids, so a value kept past its renderer's death can later name an
 * unrelated helper of ours. Both produce a number that looks entirely reasonable and is charged
 * to the wrong tab.
 *
 * So the rule this encodes is: only a commit may set a value, and anything that ends the
 * renderer's life clears it. Unknown is always an acceptable answer - the strip simply omits the
 * figure - and is always preferable to a plausible wrong one.
 */
internal class RendererPid {
    @Volatile
    private var pid: Int? = null

    /** The current pid, or null when unknown. A plain volatile read, safe from any thread. */
    val value: Int? get() = pid

    /**
     * A main-frame document committed on [pid].
     *
     * Accepts null and stores it, which is the point rather than an oversight: the caller passes
     * null when the frame or the pid could not be read, and overwriting with "unknown" is what
     * stops the previous document's renderer being reported for the new one.
     */
    fun onCommit(pid: Int?) {
        this.pid = pid
    }

    /** The renderer, or the browser, is gone. */
    fun onGone() {
        pid = null
    }
}
