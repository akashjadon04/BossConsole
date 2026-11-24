package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.js.JsAccessible

/**
 * Asks the PAGE whether it wants to own the find shortcut, so a site with its own
 * find-in-page (Google Sheets, Docs, Notion) keeps it instead of being overridden.
 *
 * ## Why this exists at all
 *
 * JxBrowser's key hook gives us exactly two answers - `PressKeyCallback.Response.proceed()`
 * and `.suppress()` - and no way to learn whether the page did anything with a key it was
 * given. There is no API for this in 9.4.0; the search package is `TextFinder` plus
 * `FindOptions`/`FindResult` and nothing else. So the page has to tell us.
 *
 * Chrome answers the same question the same way. Ctrl/Cmd+F is a *non-reserved* accelerator
 * there: the page sees the key first, and if it calls `preventDefault()` Chrome skips its own
 * find bar. That is the entire mechanism behind Sheets' find working in Chrome, and matching
 * it means we inherit every site that already knows how to opt in - no allowlist to maintain.
 *
 * ## How the verdict is read
 *
 * One capture-phase `keydown` listener on `window`. Capture on `window` is the FIRST thing in
 * the dispatch path, and this is injected at document start, so it runs before any handler the
 * page registers.
 *
 * The verdict is read from a `setTimeout(…, 0)` rather than from the listener body, and that
 * deferral is load-bearing twice over:
 *
 *  - `defaultPrevented` is only final once dispatch has finished. Read inline, in the first
 *    listener to run, it is always false.
 *  - It survives `stopPropagation()`. The obvious alternative - a second listener at the
 *    bubble phase on `window`, reading the flag there - never fires when a page stops
 *    propagation partway, which is precisely what Sheets and Docs do. The timeout is queued
 *    from the capture phase, so nothing the page does downstream can cancel it.
 *
 * ## What a page can do with this
 *
 * The only lever is "suppress BOSS's find bar on this page", and calling `preventDefault()` on
 * the key already achieves exactly that - which is the sanctioned way to ask. The bridge drops a
 * report with no decision waiting on it, so a page calling it in a loop is cheap to serve.
 *
 * **One thing it does add, stated precisely rather than waved away:** the bridge is published on
 * every frame and `report` carries no frame identity, so a NON-FOCUSED iframe can answer on the
 * focused document's behalf by polling within the deadline. The consequence is bounded - our bar
 * does not open for that press, and the user presses again - and any frame on the page could
 * already claim the chord for itself by calling `preventDefault()`. Carrying a per-frame token in
 * the report would close it; it is not closed today.
 */
internal object BrowserFindKeyProbe {
    /** Property the bridge is published on, per frame. Matched by [source]. */
    const val BRIDGE_PROPERTY: String = "__bossFindProbe"

    /** Guard so re-injection into the same document is a no-op. */
    private const val STARTED_FLAG = "__bossFindProbeStarted"

    /** [report] argument when the page called `preventDefault()` on the find chord. */
    const val VERDICT_HANDLED: String = "handled"

    /** [report] argument when the find chord passed through the page untouched. */
    const val VERDICT_FREE: String = "free"

    /**
     * The probe source.
     *
     * Reads nothing from the DOM and nothing from the event but its key identity and modifier
     * flags, so unlike [BrowserInteractionScript] there is no privacy surface to describe.
     *
     * `event.code` is the physical key, which is what an accelerator is defined against - a
     * Dvorak or AZERTY layout puts a different character on that key, and Chromium fires the
     * accelerator on the position either way. `keyCode` is the fallback purely for a frame
     * whose engine predates `code`; both are checked rather than one, because getting this
     * wrong fails SILENTLY (no report, so the deadline elapses and our bar opens over the
     * page's own).
     */
    val source: String =
        """
        (function () {
          if (window.$STARTED_FLAG) return;
          window.$STARTED_FLAG = true;
          try {
            var isMac = (navigator.platform || '').toLowerCase().indexOf('mac') === 0;
            window.addEventListener('keydown', function (e) {
              try {
                var isF = e.code === 'KeyF' || e.keyCode === 70;
                if (!isF) return;
                // Mirrors the host's isMainModifierDown: Cmd on macOS, Ctrl elsewhere, and
                // never both. Shift and Alt are excluded here for the same reason they are
                // excluded there - Cmd+Shift+F is Focus Mode, not find.
                var mod = isMac ? (e.metaKey && !e.ctrlKey) : (e.ctrlKey && !e.metaKey);
                if (!mod || e.shiftKey || e.altKey) return;
                setTimeout(function () {
                  try {
                    var bridge = window.$BRIDGE_PROPERTY;
                    if (bridge) {
                      bridge.report(e.defaultPrevented ? '$VERDICT_HANDLED' : '$VERDICT_FREE');
                    }
                  } catch (ignored) {}
                }, 0);
              } catch (ignored) {}
            }, true);
          } catch (ignored) {}
        })();
        """.trimIndent()
}

/**
 * Page→host end of [BrowserFindKeyProbe]. One instance per browser, published on every
 * frame's `window.__bossFindProbe`.
 *
 * [report] runs on a JxBrowser thread and must never block or throw into the page's JS
 * thread - a throw here surfaces in the site's own console and can break its scripts. The
 * exception CLASS is logged, never a message: this is reached from arbitrary pages, and a
 * message could carry page detail into a log line.
 */
internal class BrowserFindKeyProbeBridge(
    private val onVerdict: (pageHandledKey: Boolean) -> Unit,
) {
    private val logger = BossLogger.forComponent("BrowserFindKeyProbeBridge")

    @JsAccessible
    fun report(verdict: String) {
        try {
            when (verdict) {
                BrowserFindKeyProbe.VERDICT_HANDLED -> onVerdict(true)

                BrowserFindKeyProbe.VERDICT_FREE -> onVerdict(false)

                // Anything else is a page calling the bridge with its own argument. Dropped
                // rather than guessed: guessing "free" would let a site open our find bar it
                // never asked for, and guessing "handled" would let it suppress one the user
                // did ask for.
                else -> Unit
            }
        } catch (e: LinkageError) {
            // A wiring break rather than bad input: this class or the code it calls is not
            // what it was compiled against. Enumerated rather than caught as Throwable,
            // matching BrowserInteractionBridge - an OutOfMemoryError is not this boundary's
            // to swallow.
            report(e)
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            report(e)
        }
    }

    private fun report(error: Throwable) {
        logger.debug(
            LogCategory.BROWSER,
            "Find-key probe verdict rejected",
            mapOf("error" to (error::class.simpleName ?: "Exception")),
        )
    }
}
