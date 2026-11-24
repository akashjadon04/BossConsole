package ai.rever.boss.plugin.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import com.teamdev.jxbrowser.browser.Browser
import com.teamdev.jxbrowser.browser.callback.InjectJsCallback
import com.teamdev.jxbrowser.frame.Frame
import java.util.Collections
import java.util.WeakHashMap

/**
 * Shared owner of a browser's single `InjectJsCallback` slot.
 *
 * JxBrowser allows exactly ONE `InjectJsCallback` per [Browser]; a second
 * `browser.set(InjectJsCallback…)` silently replaces the first. More than one feature
 * can want document-start injection (e.g. the co-browse rrweb recorder on the
 * `feat/cobrowse-tab-sharing` branch), so calling `browser.set` directly makes
 * whichever registers second clobber the other.
 *
 * This dispatcher claims the slot once per browser and fans each document-start event
 * out to every registered injector. **Every** document-start injector must go through
 * [register] instead of setting the callback directly, or the clobber returns.
 *
 * Three injectors register today: [FluckEngine]'s find-key probe (every frame),
 * [BrowserHandleImpl]'s co-browse recorder, and its page-event script. That is not a
 * hypothetical list - it is why `InjectJsCallbackOwnershipTest` exists. The co-browse
 * recorder used to call `browser.set` directly, and since the find-key probe registers
 * here when the browser is created, **starting a tab share replaced the dispatcher's
 * callback and silently stopped the find-chord probe being injected for the rest of that
 * tab's life.** No error, no log line; Cmd+F simply stopped noticing that a page wanted
 * to serve its own find bar.
 *
 * An injector switches itself off by making its own body inert (each one checks the state
 * it depends on), never by removing the callback - that would take the slot away from the
 * others.
 */
internal object BrowserInjectDispatcher {
    private val logger = BossLogger.forComponent("BrowserInjectDispatcher")

    // Weak keys so entries clear when a Browser is GC'd. Each value is the ordered list
    // of injectors invoked (in registration order) at document-start.
    private val injectors: MutableMap<Browser, MutableList<(Frame) -> Unit>> =
        Collections.synchronizedMap(WeakHashMap())

    /**
     * Register a document-start [injector] for [browser]. The first registration for a
     * browser installs the shared [InjectJsCallback]; later ones just append. The
     * injector receives each frame as its context is created (guard on `frame.isMain()`
     * if you only want the top frame). Injector exceptions are swallowed so one can't
     * break the page's JS thread or starve the others.
     *
     * @return true when this injector is registered. **False means it is not**, and a caller that
     *   tracks "already registered" has to clear that flag or it will never ask again - leaving its
     *   feature inert for the browser's whole life, and worse, letting the next feature to register
     *   successfully claim the slot with only its own injector in it. Dropping the map entry here is
     *   only half the recovery; the caller owns the other half, which is why this is not `Unit`.
     */
    fun register(
        browser: Browser,
        injector: (Frame) -> Unit,
    ): Boolean {
        val alreadyClaimed =
            synchronized(injectors) {
                val existing = injectors[browser]
                if (existing != null) {
                    existing.add(injector)
                    true
                } else {
                    injectors[browser] = mutableListOf(injector)
                    false
                }
            }
        if (alreadyClaimed) return true
        return claimSlot(browser)
    }

    /**
     * Claim the browser's single callback slot for the dispatcher's fan-out.
     *
     * Split from [register] only to keep one exit per function; the interesting part is the catch.
     * On failure the map entry has to come back OUT and the caller has to be told. Leaving it meant
     * every later `register` hit the already-claimed path above, so the slot was never claimed and
     * every injector on that browser stayed inert for its whole lifetime - silently, since the
     * warning here names a registration nobody was waiting on.
     */
    private fun claimSlot(browser: Browser): Boolean {
        try {
            browser.set(
                InjectJsCallback::class.java,
                InjectJsCallback { params ->
                    val frame = params.frame()
                    val handlers = synchronized(injectors) { injectors[frame.browser()]?.toList() }.orEmpty()
                    for (handler in handlers) {
                        try {
                            handler(frame)
                        } catch (e: Throwable) {
                            logger.debug(
                                LogCategory.BROWSER,
                                "Inject handler failed",
                                mapOf("error" to (e.message ?: "")),
                            )
                        }
                    }
                    InjectJsCallback.Response.proceed()
                },
            )
            return true
        } catch (e: Throwable) {
            synchronized(injectors) { injectors.remove(browser) }
            logger.warn(LogCategory.BROWSER, "Failed to register shared InjectJsCallback", error = e)
            return false
        }
    }

    /**
     * Drop every injector registered for [browser], and release the slot.
     *
     * For a browser that is closing. Called from `BrowserHandleImpl.dispose`, because the weak keys
     * above do **not** collect this on their own: a `WeakHashMap` value strongly references
     * anything it captures, and both registered injectors are lambdas that close over their
     * `BrowserHandleImpl` (or, in FluckEngine's case, the browser itself) - which holds the key. The
     * entry therefore pins a whole handle per closed tab. Classic `WeakHashMap` footgun, and the
     * reason this method exists rather than a comment claiming the map handles it.
     *
     * `browser.remove(InjectJsCallback…)` is deliberately NOT called here, and
     * `InjectJsCallbackOwnershipTest` forbids it: the slot is shared, so unclaiming it on one
     * handle's teardown would disable every other injector on that browser. Dropping the entry is
     * enough - the callback that remains has nothing left to fan out to, and the browser is closing.
     */
    fun unregister(browser: Browser) {
        synchronized(injectors) { injectors.remove(browser) }
    }
}
