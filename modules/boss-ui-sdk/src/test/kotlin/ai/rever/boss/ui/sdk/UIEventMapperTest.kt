package ai.rever.boss.ui.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ai.rever.boss.ipc.proto.UIEvent as ProtoUIEvent

/**
 * Every `UIEvent` oneof case must map in both directions (issue #34 items 2 and 8).
 *
 * The host previously built these events inline from a `(eventType, eventData)` string pair with no
 * `"selection"` branch, so dropdown picks reached the plugin as a `UIEvent` with an unset oneof, and
 * the four families the proto declares but nothing mapped (`key`, `scroll`, `focus`, `lifecycle`)
 * had no representation at all.
 */
class UIEventMapperTest {
    private val surface = "surface-1"
    private val node = "w7"
    private val timestamp = 1_234_567L

    private fun roundTrip(event: WidgetEvent): ProtoUIEvent {
        val wire = UIEventMapper.toProto(surface, node, event, timestamp)

        assertEquals(surface, wire.surfaceId)
        assertEquals(node, wire.targetNodeId)
        assertEquals(timestamp, wire.timestamp)
        assertOneofSet(wire)
        assertEquals(EmittedEvent(node, event), UIEventMapper.fromProto(wire))
        return wire
    }

    private fun assertOneofSet(wire: ProtoUIEvent) {
        assertTrue(
            wire.eventCase != ProtoUIEvent.EventCase.EVENT_NOT_SET,
            "oneof must be set, else the plugin receives an uninterpretable event",
        )
    }

    @Test
    fun `click maps onto the click case`() {
        val wire = roundTrip(WidgetEvent.Click("save_event"))
        assertEquals(ProtoUIEvent.EventCase.CLICK, wire.eventCase)
        assertEquals("save_event", wire.click.eventId)
    }

    @Test
    fun `text change maps onto the text change case`() {
        val wire = roundTrip(WidgetEvent.TextChange("typed value"))
        assertEquals(ProtoUIEvent.EventCase.TEXT_CHANGE, wire.eventCase)
        assertEquals("typed value", wire.textChange.newValue)
    }

    @Test
    fun `toggle maps onto the toggle case`() {
        val wire = roundTrip(WidgetEvent.Toggle(checked = true))
        assertEquals(ProtoUIEvent.EventCase.TOGGLE, wire.eventCase)
        assertEquals(true, wire.toggle.checked)
    }

    @Test
    fun `selection carries both the value and its index`() {
        val wire = roundTrip(WidgetEvent.Selection("beta", 1))
        assertEquals(ProtoUIEvent.EventCase.SELECTION, wire.eventCase)
        assertEquals("beta", wire.selection.selectedValue)
        assertEquals(1, wire.selection.selectedIndex)
    }

    @Test
    fun `key carries the code and every modifier`() {
        val wire =
            roundTrip(
                WidgetEvent.Key(keyCode = 65, ctrl = true, alt = false, shift = true, meta = false),
            )
        assertEquals(ProtoUIEvent.EventCase.KEY, wire.eventCase)
        assertEquals(65, wire.key.keyCode)
        assertEquals(true, wire.key.ctrl)
        assertEquals(false, wire.key.alt)
        assertEquals(true, wire.key.shift)
        assertEquals(false, wire.key.meta)
    }

    @Test
    fun `scroll carries both deltas`() {
        val wire = roundTrip(WidgetEvent.Scroll(deltaX = -3.5f, deltaY = 12f))
        assertEquals(ProtoUIEvent.EventCase.SCROLL, wire.eventCase)
        assertEquals(-3.5f, wire.scroll.deltaX)
        assertEquals(12f, wire.scroll.deltaY)
    }

    @Test
    fun `focus maps onto the focus case`() {
        val gained = roundTrip(WidgetEvent.Focus(hasFocus = true))
        assertEquals(ProtoUIEvent.EventCase.FOCUS, gained.eventCase)
        assertEquals(true, gained.focus.hasFocus)
        // `false` is a meaningful value, not an absent one: focus loss must still map.
        val lost = roundTrip(WidgetEvent.Focus(hasFocus = false))
        assertEquals(ProtoUIEvent.EventCase.FOCUS, lost.eventCase)
    }

    @Test
    fun `lifecycle maps onto the lifecycle case`() {
        val wire = roundTrip(WidgetEvent.Lifecycle(LifecycleStates.CREATED))
        assertEquals(ProtoUIEvent.EventCase.LIFECYCLE, wire.eventCase)
        assertEquals("created", wire.lifecycle.lifecycleState)
    }

    @Test
    fun `unknown lifecycle states survive the round trip`() {
        roundTrip(WidgetEvent.Lifecycle("some-future-state"))
    }

    @Test
    fun `surface-level events may carry an empty node id`() {
        val emitted = EmittedEvent("", WidgetEvent.Lifecycle(LifecycleStates.CREATED))
        val wire = UIEventMapper.toProto(surface, emitted, timestamp)
        assertEquals("", wire.targetNodeId)
        assertEquals(EmittedEvent("", WidgetEvent.Lifecycle("created")), UIEventMapper.fromProto(wire))
    }

    @Test
    fun `an unset oneof reads back as null rather than a fabricated event`() {
        val empty =
            ProtoUIEvent
                .newBuilder()
                .setSurfaceId(surface)
                .setTargetNodeId(node)
                .build()
        assertNull(UIEventMapper.fromProto(empty))
    }

    @Test
    fun `every widget event variant is mapped`() {
        val allVariants =
            listOf(
                WidgetEvent.Click(""),
                WidgetEvent.TextChange(""),
                WidgetEvent.Toggle(false),
                WidgetEvent.Selection("", 0),
                WidgetEvent.Key(0),
                WidgetEvent.Scroll(0f, 0f),
                WidgetEvent.Focus(false),
                WidgetEvent.Lifecycle(""),
            )
        // One per proto oneof case (EVENT_NOT_SET excluded): a new sealed variant without a mapping is
        // a compile error, and a new proto case without a variant shows up here as a count mismatch.
        assertEquals(ProtoUIEvent.EventCase.entries.size - 1, allVariants.size)
        for (event in allVariants) {
            val wire = UIEventMapper.toProto(surface, node, event, timestamp)
            assertOneofSet(wire)
            assertNotNull(UIEventMapper.fromProto(wire))
        }
    }
}
