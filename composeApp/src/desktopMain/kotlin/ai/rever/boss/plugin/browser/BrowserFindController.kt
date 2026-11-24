package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.SystemUtils
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.search.FindOptions
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.swing.SwingUtilities
import javax.swing.Timer

/** How long typing settles before a search is issued. */
private const val SEARCH_DEBOUNCE_MS = 150

/**
 * How long the page gets to claim the find chord before we open our own bar.
 *
 * Generous enough for a `setTimeout(…, 0)` on a busy main thread, short enough that a page
 * which cannot report at all (see [BrowserFindKeyProbe]) does not feel like a stall.
 */
private const val PAGE_VERDICT_DEADLINE_MS = 150

/**
 * How long a find request is treated as already served for the same browser.
 *
 * Held-key auto-repeat delivers the find chord over and over, and each one would otherwise restart
 * the page-verdict deadline - so the bar would not appear until the key was released. Also the
 * belt-and-braces guard against the two entry paths both serving one press: they are mutually
 * exclusive by construction (whichever of AWT and Chromium gets the key, the other never sees it),
 * but that rests on native focus behaviour that differs between rendering modes, and a
 * double-toggle degrades into a bar that flickers rather than into a visible error.
 */
private const val SINGLE_FLIGHT_MS = 200L

/**
 * Cmd+G on macOS, Ctrl+G elsewhere, and F3 everywhere - the two conventions for "find again".
 *
 * The one rule, in one place: `FluckEngine`'s key callback serves this chord when the page has
 * focus and [BrowserFindBar] serves it when the field does, and the first version of the second one
 * gated on the Meta modifier alone - which made find-again from the field macOS-only, with nothing
 * else able to serve it because the bar owns keyboard focus while it is up.
 */
@Suppress("ReturnCount")
internal fun isFindAgainChord(
    isF3: Boolean,
    isG: Boolean,
    meta: Boolean,
    ctrl: Boolean,
): Boolean {
    if (isF3) return true
    if (!isG) return false
    return if (SystemUtils.isMacOS) meta && !ctrl else ctrl && !meta
}

/**
 * Observable find-in-page state for one browser. Read by [BrowserFindBar], written only by
 * [BrowserFindController] on the AWT event thread.
 */
internal class BrowserFindState {
    var visible by mutableStateOf(false)

    var query by mutableStateOf("")

    var matchCase by mutableStateOf(false)

    var currentMatch by mutableStateOf(0)

    var totalMatches by mutableStateOf(0)

    /**
     * Whether a search for the CURRENT [query] has finished.
     *
     * The counter renders nothing until this is true, which is what removes the red `0/0`
     * flash on every keystroke: Chromium reports progress updates while a search runs, and
     * during those `numberOfMatches()` is a partial tally and `selectedMatch()` may not be
     * meaningful yet. Showing the previous query's count instead would be worse - a real
     * number, for the wrong text.
     */
    var settled by mutableStateOf(false)

    /**
     * Bumped when the field should take focus and select its contents.
     *
     * A tick rather than a boolean because the request repeats: Cmd+F with the bar already
     * open re-focuses it (what Chrome, Safari and Firefox all do), and a boolean already
     * true cannot express a second request.
     */
    var focusTick by mutableStateOf(0)
}

/**
 * The single owner of find-in-page: state, the `TextFinder` conversation, and the decision of
 * whether the page or BOSS serves the find chord.
 *
 * Replaces the Swing `JDialog` bar that used to live in `FluckEngine`. That one was reachable
 * only from `PressKeyCallback`, positioned itself against the whole window (so both panes of a
 * split stacked their bars in one corner), snapshotted theme colours at construction, and
 * suppressed the key so a page could never serve its own find. See [BrowserFindBar] for the UI
 * and [BrowserFindKeyProbe] for how the page is asked.
 *
 * **Everything here runs on the AWT event thread**, which is also Compose Desktop's
 * composition thread, so state writes and recomposition stay ordered. `TextFinder` replies
 * arrive on a JxBrowser thread and are hopped across explicitly.
 */
@Suppress("TooManyFunctions")
internal object BrowserFindController {
    private val logger = BossLogger.forComponent("BrowserFindController")

    private val states = ConcurrentHashMap<Browser, BrowserFindState>()
    private val locks = ConcurrentHashMap<Browser, ReentrantReadWriteLock>()
    private val debounces = ConcurrentHashMap<Browser, Timer>()
    private val pendingVerdicts = ConcurrentHashMap<Browser, Timer>()
    private val lastRequestAtMs = ConcurrentHashMap<Browser, Long>()

    /**
     * Bounded, unlike a plain map keyed by window id: `dispose(browser)` cannot know which window a
     * shortcut claim belonged to, so nothing would ever prune it over a long session with many
     * windows. Only the most recent claims can matter - the window is [SINGLE_FLIGHT_MS] - so the
     * oldest entry is dropped once the map exceeds the number of windows anyone opens at once.
     */
    private val lastShortcutAtMs = ConcurrentHashMap<String, Long>()

    /**
     * Which search session a reply belongs to.
     *
     * Not decoration. `TextFinderImpl.find` allocates a fresh request id per call and keeps a
     * live consumer for each, so the debounce - which does not and cannot cancel a search
     * already in flight - leaves several sessions reporting at once. Without this the label
     * shows whichever session FINISHED last, which is not the one for the latest query.
     */
    private val epochs = ConcurrentHashMap<Browser, AtomicLong>()

    /**
     * Browsers that have been disposed, so teardown is final.
     *
     * Without this every accessor here resurrects its own entry - `stateFor`, `bumpEpoch` and
     * `withFinder` all use `computeIfAbsent`, `claimRequest` uses `put` - so a key event or a
     * verdict arriving after the tab closed re-created the state, and `awaitPageVerdict` re-created
     * a live `Timer` with it.
     *
     * A `Collections.newSetFromMap(WeakHashMap())` rather than a strong set: this is the one
     * structure that outlives the browser on purpose, so it must not be the thing that retains it.
     * Same choice, for the same reason, as [BrowserInjectDispatcher]'s injector map.
     */
    private val disposedBrowsers: MutableSet<Browser> =
        java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))

    /**
     * Adopt [browser]. [lock] should be the owning handle's browser lock so find calls take the
     * same read lock as every other access to it; a browser created outside a
     * [BrowserHandleImpl] gets its own.
     */
    fun register(
        browser: Browser,
        lock: ReentrantReadWriteLock = ReentrantReadWriteLock(),
    ) {
        locks.putIfAbsent(browser, lock)
        epochs.putIfAbsent(browser, AtomicLong())
    }

    /** Observable state for [browser], created on first use. */
    fun stateFor(browser: Browser): BrowserFindState = states.computeIfAbsent(browser) { BrowserFindState() }

    /**
     * Serve the find chord that JxBrowser just delivered for [browser].
     *
     * @return true when the key should be SUPPRESSED (the bar is already up, so it is ours),
     *   false when it should PROCEED to the page. Proceeding is what lets a site with its own
     *   find-in-page keep it; the verdict from [BrowserFindKeyProbe] then decides whether our
     *   bar opens too. See [PAGE_VERDICT_DEADLINE_MS] for what happens when no verdict arrives.
     */
    @Suppress("ReturnCount")
    fun onFindKeyFromPage(browser: Browser): Boolean {
        if (isDisposed(browser)) return false
        // Checked BEFORE the single-flight claim, not after: a bar that is up owns the chord for as
        // long as it is up, and gating this on the claim swallowed a fast second press - suppressing
        // the key while doing nothing with it.
        if (stateFor(browser).visible) {
            onEdt { focusField(browser) }
            return true
        }
        // A verdict is already pending for this press, which is what held-key auto-repeat looks
        // like. Proceed again so the page keeps seeing the key, but do not restart the deadline -
        // that would delay our bar for as long as the key is held.
        if (!claimRequest(browser)) return false
        onEdt { awaitPageVerdict(browser) }
        return false
    }

    /**
     * Serve the find chord that AWT intercepted before the browser could see it - the
     * `browser.find` keymap action.
     *
     * Which of the two paths runs is not a choice we make: under HARDWARE_ACCELERATED the
     * shared-surface widget has no focus wiring, so a tab that has just been re-shown holds no
     * keyboard focus and AWT gets the key; under OFF_SCREEN the Compose widget is the AWT focus
     * owner and the interceptor consumes the key before it can be forwarded.
     *
     * Rather than fork the behaviour, hand the key to the browser and let the ONE decision
     * point above run. Otherwise a page-owned Cmd+F would work or not depending on which
     * rendering mode is active, which is exactly the flakiness this change exists to remove.
     */
    @Suppress("ReturnCount")
    fun onFindKeyFromShortcut(browser: Browser) {
        if (isDisposed(browser)) return
        if (stateFor(browser).visible) {
            onEdt { focusField(browser) }
            return
        }
        if (!claimRequest(browser)) return
        // Armed BEFORE the dispatch, and this ordering is the whole robustness of this path.
        // Everything after the dispatch depends on JxBrowser re-entering this browser's own
        // PressKeyCallback for a programmatically dispatched key. If it does not, and the deadline
        // were only started there, Cmd+F on the AWT-focus path would do exactly what it did before
        // this change: nothing - the original bug, restored on the one path this exists to repair.
        //
        // Arming first makes every outcome end in something happening: the re-entry finds the claim
        // taken and proceeds without touching the timer, a page that claims the chord resolves it
        // through the probe, and silence resolves it through the deadline.
        onEdt { awaitPageVerdict(browser) }
        if (browser.isClosed) return
        try {
            val modifiers =
                com.teamdev.jxbrowser.ui.KeyModifiers
                    .newBuilder()
                    .apply { if (SystemUtils.isMacOS) metaDown(true) else controlDown(true) }
                    .build()
            browser.dispatch(
                com.teamdev.jxbrowser.ui.event.KeyPressed
                    .newBuilder(com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_F)
                    .keyModifiers(modifiers)
                    .build(),
            )
            browser.dispatch(
                com.teamdev.jxbrowser.ui.event.KeyReleased
                    .newBuilder(com.teamdev.jxbrowser.ui.KeyCode.KEY_CODE_F)
                    .keyModifiers(modifiers)
                    .build(),
            )
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            // A tab closing under us is the expected case. Resolve the decision this armed rather
            // than calling open() beside a timer that is still running, so the bar opens once.
            logger.debug(
                LogCategory.BROWSER,
                "Could not hand the find chord to the page - opening the BOSS find bar",
                mapOf("error" to (e::class.simpleName ?: "Exception")),
            )
            onPageVerdict(browser, pageHandledKey = false)
        }
    }

    /**
     * Serve the "find again" chord (Cmd+G / Shift+Cmd+G) delivered for [browser].
     *
     * @return true when the key should be SUPPRESSED. Only claimed while our bar is up with
     *   something in it - otherwise the chord does nothing here and belongs to the page, which may
     *   well have its own meaning for it.
     *
     * Deliberately NOT single-flighted, unlike the find chord: repeat presses advancing through
     * matches is the entire purpose, so collapsing them would break it. Held-key auto-repeat
     * walking the matches is what Chrome does too.
     */
    @Suppress("ReturnCount")
    fun onFindAgainKey(
        browser: Browser,
        backward: Boolean,
    ): Boolean {
        if (isDisposed(browser)) return false
        val state = stateFor(browser)
        if (!state.visible || state.query.isEmpty()) return false
        onEdt { if (backward) previous(browser) else next(browser) }
        return true
    }

    /** The page's answer to a chord we proceeded with. See [BrowserFindKeyProbe]. */
    fun onPageVerdict(
        browser: Browser,
        pageHandledKey: Boolean,
    ) = onEdt {
        if (isDisposed(browser)) return@onEdt
        // No decision waiting means the report is late, or a page called the bridge on its own.
        // Either way there is nothing to resolve, which is also what makes a page spamming the
        // bridge cheap to serve.
        val timer = pendingVerdicts.remove(browser) ?: return@onEdt
        timer.stop()
        if (!pageHandledKey) open(browser)
    }

    fun setQuery(
        browser: Browser,
        text: String,
    ) = onEdt {
        val state = stateFor(browser)
        if (state.query == text) return@onEdt
        state.query = text
        // Cleared BEFORE the search, so the counter goes blank rather than briefly showing the
        // previous query's total against the new text.
        state.settled = false
        if (text.isEmpty()) {
            state.totalMatches = 0
            state.currentMatch = 0
            debounces.remove(browser)?.stop()
            bumpEpoch(browser)
            withFinder(browser) { it.stopFindingAndClearSelection() }
            return@onEdt
        }
        debounces
            .computeIfAbsent(browser) {
                Timer(SEARCH_DEBOUNCE_MS) { runFind(browser, backward = false) }.apply { isRepeats = false }
            }.restart()
    }

    fun setMatchCase(
        browser: Browser,
        enabled: Boolean,
    ) = onEdt {
        val state = stateFor(browser)
        if (state.matchCase == enabled) return@onEdt
        state.matchCase = enabled
        state.settled = false
        runFind(browser, backward = false)
    }

    fun next(browser: Browser) = onEdt { runFind(browser, backward = false) }

    fun previous(browser: Browser) = onEdt { runFind(browser, backward = true) }

    fun open(browser: Browser) =
        onEdt {
            val state = stateFor(browser)
            state.visible = true
            focusField(browser)
            // Re-opening on a query that is still there searches again rather than showing a count
            // from before the page may have changed under it.
            if (state.query.isNotEmpty()) runFind(browser, backward = false)
        }

    fun close(browser: Browser) =
        onEdt {
            val state = stateFor(browser)
            state.visible = false
            state.settled = false
            debounces.remove(browser)?.stop()
            pendingVerdicts.remove(browser)?.stop()
            bumpEpoch(browser)
            withFinder(browser) {
                // KEEP, not clear. Clearing drops the selection, which scrolls the reader back to
                // wherever they were before searching - throwing away the very result they went
                // looking for. Chrome keeps the match; an empty query has no match to keep.
                if (state.query.isEmpty()) it.stopFindingAndClearSelection() else it.stopFindingAndKeepSelection()
            }
            // Our bar held keyboard focus in its own window; hand it back so the page is
            // immediately scrollable and typeable again.
            if (!browser.isClosed) {
                try {
                    browser.focus()
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    logger.debug(
                        LogCategory.BROWSER,
                        "Could not return focus to the page after closing find",
                        mapOf("error" to (e::class.simpleName ?: "Exception")),
                    )
                }
            }
        }

    /** Release everything held for [browser]. Safe to call more than once. */
    fun dispose(browser: Browser) {
        // Recorded FIRST: the removals below race a verdict or a key event already in flight, and
        // whichever of them lands next must find the browser dead rather than re-create its state.
        disposedBrowsers += browser
        debounces.remove(browser)?.stop()
        pendingVerdicts.remove(browser)?.stop()
        states.remove(browser)
        locks.remove(browser)
        epochs.remove(browser)
        lastRequestAtMs.remove(browser)
    }

    private fun isDisposed(browser: Browser): Boolean = browser in disposedBrowsers

    // ============================================================
    // internals
    // ============================================================

    /**
     * Start the deadline that opens our bar if the page never answers.
     *
     * The deadline is not a safety net for a rare case - it is the normal path for every
     * document [BrowserFindKeyProbe]'s script cannot reach: the built-in PDF viewer, network
     * error pages, `about:` URLs, and any frame where injection failed.
     */
    private fun awaitPageVerdict(browser: Browser) {
        pendingVerdicts.remove(browser)?.stop()
        val timer =
            Timer(PAGE_VERDICT_DEADLINE_MS) {
                if (pendingVerdicts.remove(browser) != null) open(browser)
            }.apply { isRepeats = false }
        pendingVerdicts[browser] = timer
        timer.start()
    }

    private fun focusField(browser: Browser) {
        stateFor(browser).focusTick++
    }

    private fun bumpEpoch(browser: Browser): Long = epochs.computeIfAbsent(browser) { AtomicLong() }.incrementAndGet()

    private fun runFind(
        browser: Browser,
        backward: Boolean,
    ) {
        debounces[browser]?.stop()
        val state = stateFor(browser)
        val query = state.query
        if (query.isEmpty()) return
        val epoch = bumpEpoch(browser)
        val options =
            FindOptions
                .newBuilder()
                .matchCase(state.matchCase)
                .searchBackward(backward)
                .build()
        withFinder(browser) { finder ->
            finder.find(query, options) { result ->
                // JxBrowser thread. Hop before touching snapshot state.
                onEdt {
                    if (epochs[browser]?.get() != epoch) return@onEdt
                    // A progress update, not an answer: numberOfMatches() is a running tally
                    // here and selectedMatch() need not be meaningful yet. Ignoring these is
                    // what stops the counter flashing 0/0 on every keystroke.
                    if (result.isSearching()) return@onEdt
                    state.totalMatches = result.numberOfMatches()
                    state.currentMatch = result.selectedMatch()
                    state.settled = true
                }
            }
        }
    }

    /**
     * Run [block] against [browser]'s text finder, or do nothing.
     *
     * The `isClosed` check is the cheap path and the try/catch is the real guard - the same
     * split [LockedBrowser.urlOrEmpty] documents. It matters more here than elsewhere in this
     * file because two of the callers are timers: a debounce can fire up to
     * [SEARCH_DEBOUNCE_MS] after the tab it belongs to was closed.
     */
    private inline fun withFinder(
        browser: Browser,
        block: (LockedTextFinder) -> Unit,
    ) {
        if (browser.isClosed) return
        // Fails closed on a missing lock rather than minting a fresh one. A substituted lock is a
        // DIFFERENT object from the handle's, which quietly drops the mutual exclusion that the
        // register(browser, browserLock) overload exists to provide - and the only way the entry is
        // absent is that the browser was disposed, where skipping the search is what we want anyway.
        val lock = locks[browser] ?: return
        try {
            block(LockedBrowser(browser, lock).textFinder())
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            logger.debug(
                LogCategory.BROWSER,
                "Find operation failed",
                mapOf("error" to (e::class.simpleName ?: "Exception")),
            )
        }
    }

    /**
     * Collapse a request repeated inside [SINGLE_FLIGHT_MS] for the same browser.
     *
     * @return true if the caller owns this request.
     */
    private fun claimRequest(browser: Browser): Boolean {
        val now = nowMs()
        val previous = lastRequestAtMs.put(browser, now)
        return previous == null || now - previous >= SINGLE_FLIGHT_MS
    }

    /**
     * Claim one `browser.find` event for [windowId], so only the first responder serves it.
     *
     * `browserFindEvents` is a broadcast and every composed browser surface in the window
     * collects it. Two can believe they qualify at once: `LocalIsPanelActive` defaults to true, so
     * a browser rendered in a sidebar slot reads as active alongside the main panel's. Without
     * this, one Cmd+F would open two find bars in one window.
     *
     * Per WINDOW, unlike [claimRequest] which is per browser: the point is to pick one of several
     * browsers, which a per-browser guard cannot do.
     */
    fun claimShortcut(windowId: String): Boolean {
        val now = nowMs()
        val previous = lastShortcutAtMs.put(windowId, now)
        if (lastShortcutAtMs.size > MAX_TRACKED_WINDOWS) {
            lastShortcutAtMs.entries
                .minByOrNull { it.value }
                ?.let { lastShortcutAtMs.remove(it.key, it.value) }
        }
        return previous == null || now - previous >= SINGLE_FLIGHT_MS
    }

    /**
     * Monotonic milliseconds, for the elapsed-time windows above.
     *
     * `System.currentTimeMillis()` is wall clock: an NTP correction or a sleep/wake step can refuse
     * a claim that should have been granted, or grant one that 200 ms of real time has not covered.
     */
    private fun nowMs(): Long = System.nanoTime() / 1_000_000

    /** Far above any real window count; a backstop, not a policy. */
    private const val MAX_TRACKED_WINDOWS = 64

    private inline fun onEdt(crossinline block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater { block() }
    }
}
