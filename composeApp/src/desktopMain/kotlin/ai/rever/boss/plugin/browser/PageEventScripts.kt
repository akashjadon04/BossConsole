package ai.rever.boss.plugin.browser

/**
 * The wrapper a plugin's page-event script is evaluated inside.
 *
 * Pure and separate from [BrowserHandleImpl] on purpose. This wrapper *is* the published contract -
 * it decides that the bridge arrives as a parameter rather than as a `window` property - and the
 * contract already drifted from the implementation once, silently, in exactly the way a plugin
 * author only discovers as "my events never arrive". `PageEventScriptsTest` pins the shape so the
 * next divergence is a failing assertion instead. Same reasoning as [CoBrowseScripts] holding its
 * own JS rather than inlining it at the call site.
 */
internal object PageEventScripts {
    /**
     * Wrap [script] so it receives the bridge as a parameter named [PAGE_EVENT_BRIDGE], and take the
     * hand-over slot back off `window` in the same evaluation.
     *
     * Four things this has to get right, in order:
     *
     * 1. **Read [slot] before anything else.** It is the only moment the bridge is reachable.
     * 2. **Delete it immediately**, in a `try` that cannot skip step 3. A page then has nothing to
     *    replace (it could otherwise intercept what the script posts), nothing to call (forging
     *    events into the plugin's sink), and nothing to attribute (see the honest limits below).
     * 3. **Pass the bridge in as a parameter**, so the script's own scope is the only place it lives.
     * 4. **Report a throwing script to the page console, not the host log.** A script that dies at
     *    evaluation must not do so invisibly - that is what turns a contract mismatch into a
     *    week-long mystery - but the host must not log the script body either, since a page-event
     *    script legitimately names the fields the user typed into.
     *
     * **What the slot name does and does not buy.** It is random per injection, so it cannot be
     * *guessed* in the gap between the host writing it and this script running - a gap that only
     * exists for the one-off injection into a document already running page script, since at
     * document start no page code has executed. It does NOT defeat enumeration: `Object.keys(window)`
     * in that window finds a new key regardless of its name. The name is therefore deliberately
     * free of any recognisable prefix, so what enumeration finds is an anonymous key rather than a
     * "this is BOSS" bit. Closing that gap entirely needs a non-enumerable property, which
     * `JsObject.putProperty` cannot express.
     */
    fun injection(
        slot: String,
        script: String,
    ): String =
        """
        (function () {
            var bridge = window.$slot;
            try { delete window.$slot; } catch (e) { window.$slot = undefined; }
            try {
                (function ($PAGE_EVENT_BRIDGE) {
        $script
                })(bridge);
            } catch (e) {
                // The page's console, never the host log: a page-event script names page fields.
                try { console.error('BOSS page event script failed', e && e.message); } catch (e2) { }
            }
        })();
        """.trimIndent()

    /**
     * A fresh hand-over slot name.
     *
     * No prefix, and lower-case-alpha first so it is always a valid JS identifier. See [injection]
     * for why the absence of a prefix is the point.
     */
    fun newSlot(random: () -> String): String = "b" + random().filter { it.isLetterOrDigit() }.take(24)
}
