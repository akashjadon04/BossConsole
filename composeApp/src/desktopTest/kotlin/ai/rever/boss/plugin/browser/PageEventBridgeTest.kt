package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The page-to-plugin boundary for a plugin-supplied script.
 *
 * Everything here is about the parts of the KDoc that say IMPORTANT, because each one is a way the
 * page's own JS thread gets hurt by host code: a throwing sink, an unbounded payload, an unbounded
 * rate. `BrowserInteractionBridgeTest` is the precedent, and this bridge is a step up in
 * consequence - what flows through it is the user's password.
 */
class PageEventBridgeTest {
    private class Sink {
        val received = mutableListOf<Pair<String, String>>()
        val fn: (String, String) -> Unit = { url, json -> received += url to json }
    }

    @Test
    fun `a posted event reaches the sink with the host's url`() {
        val sink = Sink()
        val bridge = PageEventBridge(onEvent = sink.fn, urlProvider = { "https://example.com/login" })
        bridge.emit("""{"kind":"submit"}""")
        assertEquals(listOf("https://example.com/login" to """{"kind":"submit"}"""), sink.received)
    }

    @Test
    fun `no sink is not an error`() {
        // The uninstall path clears onEvent while a document-start hook may be mid-flight, so this
        // is a normal state and not a defensive nicety.
        PageEventBridge(onEvent = null).emit("{}")
    }

    @Test
    fun `a throwing sink never reaches the page's JS thread`() {
        // emit runs inside the page's own event dispatch. An exception escaping here would leave
        // the submit the user just performed half-dispatched.
        val bridge = PageEventBridge(onEvent = { _, _ -> error("sink exploded") })
        bridge.emit("{}")
    }

    @Test
    fun `a throwing url provider loses the url, not the event`() {
        val sink = Sink()
        val bridge = PageEventBridge(onEvent = sink.fn, urlProvider = { error("browser gone") })
        bridge.emit("{}")
        assertEquals(listOf("" to "{}"), sink.received)
    }

    @Test
    fun `swapping the sink redirects delivery`() {
        // How setPageEventScript re-points an existing bridge: one instance serves every document
        // the browser loads, so a stale sink would keep receiving after an uninstall.
        val first = Sink()
        val second = Sink()
        val bridge = PageEventBridge(onEvent = first.fn)
        bridge.emit("a")
        bridge.onEvent = second.fn
        bridge.emit("b")
        bridge.onEvent = null
        bridge.emit("c")
        assertEquals(1, first.received.size)
        assertEquals(1, second.received.size)
        assertEquals("b", second.received.single().second)
    }

    @Test
    fun `an oversized payload is dropped`() {
        val sink = Sink()
        val bridge = PageEventBridge(onEvent = sink.fn)
        bridge.emit("x".repeat(PageEventBridge.MAX_PAYLOAD_CHARS + 1))
        assertTrue(sink.received.isEmpty(), "an unbounded string reached the sink")
        // The boundary itself is allowed: a cap that is off by one silently truncates real events.
        bridge.emit("y".repeat(PageEventBridge.MAX_PAYLOAD_CHARS))
        assertEquals(1, sink.received.size)
    }

    @Test
    fun `an oversized payload does not consume the rate budget`() {
        // Otherwise a page could spend the whole window on strings that were never going to be
        // forwarded, and starve the events that would have been.
        val sink = Sink()
        val bridge = PageEventBridge(onEvent = sink.fn, clock = { 0L })
        repeat(PageEventBridge.MAX_EVENTS_PER_WINDOW * 2) {
            bridge.emit("x".repeat(PageEventBridge.MAX_PAYLOAD_CHARS + 1))
        }
        bridge.emit("real")
        assertEquals(listOf("" to "real"), sink.received)
    }

    @Test
    fun `a burst is capped within one window and recovers in the next`() {
        val sink = Sink()
        var now = 0L
        val bridge = PageEventBridge(onEvent = sink.fn, clock = { now })
        repeat(PageEventBridge.MAX_EVENTS_PER_WINDOW + 20) { bridge.emit("e") }
        assertEquals(
            PageEventBridge.MAX_EVENTS_PER_WINDOW,
            sink.received.size,
            "the rate limit did not bound the burst",
        )

        // Dropped, not queued: the next window starts clean rather than replaying the overflow.
        now += PageEventBridge.RATE_WINDOW_MS
        bridge.emit("next window")
        assertEquals(PageEventBridge.MAX_EVENTS_PER_WINDOW + 1, sink.received.size)
        assertEquals("next window", sink.received.last().second)
    }

    @Test
    fun `the limits leave room for a real submit`() {
        // Three listeners can fire for one Enter keypress, and a credential pair is a few hundred
        // characters. A limit tight enough to drop that would be worse than none, because it would
        // drop exactly the event the feature exists for.
        assertTrue(PageEventBridge.MAX_EVENTS_PER_WINDOW >= 10)
        assertTrue(PageEventBridge.MAX_PAYLOAD_CHARS >= 4096)
    }
}
