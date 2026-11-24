package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.js.JsAccessible

/**
 * Page to plugin bridge for [BrowserHandleImpl.setPageEventScript].
 *
 * The instance is handed to the plugin's script as a parameter, not left on `window`: see
 * [BrowserHandleImpl.injectPageEventScript], which passes it in and deletes the slot it arrived
 * through. So in the normal case the only code holding a reference is the script itself.
 *
 * Unlike [CoBrowseBridge], which serves one known feature, this carries a payload the host does not
 * interpret at all: the plugin supplies the script, so the plugin decides what an event is and what
 * its JSON means.
 *
 * **The URL is the host's contribution, and the reason [urlProvider] exists.** A URL written into
 * the JSON is only as trustworthy as whatever wrote it, so the host reads the posting document's URL
 * itself, here, at the moment of the call. That is also strictly better than the plugin reading the
 * handle's URL afterwards: [emit] runs inside the page's own event dispatch, before the navigation a
 * submit triggers can commit, so the answer is the document that actually posted rather than
 * whatever the tab has moved on to. The first consumer uses it to decide which site a password gets
 * stored against.
 *
 * **Bounded, like [BrowserInteractionBridge] and for the same reason.** Non-blocking is not free:
 * every accepted string is an allocation in the host JVM and an item into whatever queue the sink
 * feeds. The script is not necessarily the only caller either - it holds the only reference in the
 * normal case, but a script that leaks it, or a page that wins the injection race on an
 * already-running document, can call this too. Over-sized payloads and over-rate bursts are
 * DROPPED rather than queued, because the alternative to dropping is unbounded growth.
 *
 * IMPORTANT, and the same constraint [CoBrowseBridge] documents: [emit] runs on a JxBrowser thread
 * and inside the page's own event dispatch. [onEvent] MUST be non-blocking - the caller enqueues and
 * returns. A throwing sink cannot crash the page's JS thread or leave a submit half-dispatched.
 *
 * Unlike [CoBrowseBridge] this does NOT catch bare `Throwable`: it catches [LinkageError] and
 * [Exception] separately, as [BrowserInteractionBridge] does, so a genuinely fatal `Error` still
 * escapes and a stale plugin closure after an api hot swap is reported rather than silently ending
 * the channel. Every drop gets one rate-limited debug line naming the exception CLASS - never the
 * payload, which is a plaintext secret.
 */
internal class PageEventBridge(
    @Volatile var onEvent: ((url: String, json: String) -> Unit)? = null,
    /**
     * Answers the posting document's URL. A lambda rather than a field because one bridge instance
     * serves every document this browser loads, so the value has to be read per call.
     */
    @Volatile var urlProvider: () -> String = { "" },
    /** Injected so the rate limit is testable without sleeping. */
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private var windowStartMs = 0L
    private var windowCount = 0
    private var lastNoteMs = 0L

    @JsAccessible
    fun emit(json: String) {
        val sink = onEvent ?: return
        // Size is checked before the rate budget on purpose: rejecting an over-sized payload must
        // not consume it, or a page could spend the whole window on strings that were never going to
        // be forwarded and starve the ones that would have been.
        if (json.length > MAX_PAYLOAD_CHARS) {
            note("payload over cap", "chars" to json.length.toString())
        } else if (!allow()) {
            note("rate limited", "perWindow" to MAX_EVENTS_PER_WINDOW.toString())
        } else {
            deliver(sink, json)
        }
    }

    /**
     * Hand one event to the sink.
     *
     * The catch is broad on purpose and the suppression above says so: a plugin's lambda can throw
     * anything, and this runs inside the page's own event dispatch, so what it must never do is
     * propagate. What it does NOT catch is `Error` beyond [LinkageError] - see the class KDoc.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun deliver(
        sink: (String, String) -> Unit,
        json: String,
    ) {
        try {
            // The url is read defensively: a throwing provider must not lose the event, and an empty
            // URL is something the consumer can recognise and refuse.
            sink(runCatching { urlProvider() }.getOrDefault(""), json)
        } catch (e: LinkageError) {
            // NOT merged with the Exception branch, and NOT a bare Throwable catch.
            //
            // The sink is a plugin's lambda, and its class comes from a classloader this host swaps
            // at runtime (unload-all, swap, reload-all). A NoSuchMethodError or NoClassDefEFoundError
            // from a stale closure is therefore a live possibility rather than a theoretical one -
            // and swallowing it silently kills the credential channel with nothing anywhere to say
            // why. BrowserInteractionBridge draws the same line for the same reason.
            note("sink linkage error", "type" to e.javaClass.simpleName)
        } catch (e: Exception) {
            note("sink threw", "type" to e.javaClass.simpleName)
        }
    }

    /**
     * One rate-limited debug line, carrying the exception CLASS and never the payload.
     *
     * The payload is the user's plaintext secret, so it must not reach a log - but "a page nobody
     * touched" and "a bridge that has been dropping every event" look identical without any line at
     * all, which is what made the last silent channel expensive to find. Rate-limited because the
     * cases worth reporting are exactly the ones a hostile page can drive in a loop.
     */
    private fun note(
        what: String,
        detail: Pair<String, String>,
    ) {
        val now = clock()
        val shouldLog =
            synchronized(this) {
                (now - lastNoteMs !in 0 until NOTE_WINDOW_MS).also { if (it) lastNoteMs = now }
            }
        if (!shouldLog) return
        runCatching {
            logger.debug(LogCategory.BROWSER, "Page event dropped: $what", mapOf(detail))
        }
    }

    /**
     * Fixed-window counter, synchronized because [emit] is reentrant across documents.
     *
     * A fixed window rather than a token bucket for the same reason [BrowserInteractionBridge] uses
     * one: the worst case is 2x the nominal rate across a window boundary, which is irrelevant next
     * to the unbounded case it replaces, and it costs two fields.
     */
    private fun allow(): Boolean =
        synchronized(this) {
            val now = clock()
            if (now - windowStartMs !in 0 until RATE_WINDOW_MS) {
                windowStartMs = now
                windowCount = 0
            }
            if (windowCount >= MAX_EVENTS_PER_WINDOW) return@synchronized false
            windowCount++
            true
        }

    companion object {
        private val logger = BossLogger.forComponent("PageEventBridge")

        /** At most one dropped-event line per this window, per bridge. */
        const val NOTE_WINDOW_MS = 10_000L

        /**
         * Generous next to any real event - the first consumer posts a credential pair - and small
         * enough that a page cannot allocate its way through the host heap one call at a time.
         * Matches [BrowserInteractionBridge.MAX_PAYLOAD_CHARS].
         */
        const val MAX_PAYLOAD_CHARS = 64 * 1024

        /**
         * A submit-driven channel needs single digits per second; this leaves room for a burst
         * (three listeners can fire for one Enter keypress) without leaving the rate open.
         */
        const val MAX_EVENTS_PER_WINDOW = 60
        const val RATE_WINDOW_MS = 1_000L
    }
}
