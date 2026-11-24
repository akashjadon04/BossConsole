package ai.rever.boss.ui.sdk

import ai.rever.boss.ipc.proto.ClickEvent as ProtoClickEvent
import ai.rever.boss.ipc.proto.FocusEvent as ProtoFocusEvent
import ai.rever.boss.ipc.proto.KeyEvent as ProtoKeyEvent
import ai.rever.boss.ipc.proto.LifecycleEvent as ProtoLifecycleEvent
import ai.rever.boss.ipc.proto.ScrollEvent as ProtoScrollEvent
import ai.rever.boss.ipc.proto.SelectionEvent as ProtoSelectionEvent
import ai.rever.boss.ipc.proto.TextChangeEvent as ProtoTextChangeEvent
import ai.rever.boss.ipc.proto.ToggleEvent as ProtoToggleEvent
import ai.rever.boss.ipc.proto.UIEvent as ProtoUIEvent

/**
 * Maps [WidgetEvent]s onto the `UIEvent` wire type and back.
 *
 * Every case of the proto oneof is covered in both directions. The host used to build these events
 * inline (duplicated in each surface component) from a `(eventType, eventData)` string pair, and the
 * `when` had no branch for `"selection"` — dropdown picks crossed the wire as a `UIEvent` with an
 * *unset* oneof, i.e. an event the plugin cannot interpret. A total mapping over a sealed type makes
 * that class of gap a compile error instead of silence.
 */
object UIEventMapper {
    /** Wrap a single event for the wire. */
    fun toProto(
        surfaceId: String,
        nodeId: String,
        event: WidgetEvent,
        timestampMs: Long,
    ): ProtoUIEvent =
        ProtoUIEvent
            .newBuilder()
            .setSurfaceId(surfaceId)
            .setTargetNodeId(nodeId)
            .setTimestamp(timestampMs)
            .applyEvent(event)
            .build()

    /** Wrap an already-tagged event for the wire. */
    fun toProto(
        surfaceId: String,
        emitted: EmittedEvent,
        timestampMs: Long,
    ): ProtoUIEvent = toProto(surfaceId, emitted.nodeId, emitted.event, timestampMs)

    /**
     * Read a wire event back. `null` when the oneof is unset — either a malformed sender or a case
     * added to the proto after this build, which callers must skip rather than misinterpret.
     */
    fun fromProto(event: ProtoUIEvent): EmittedEvent? {
        val widgetEvent = toWidgetEvent(event) ?: return null
        return EmittedEvent(event.targetNodeId, widgetEvent)
    }

    private fun ProtoUIEvent.Builder.applyEvent(event: WidgetEvent): ProtoUIEvent.Builder =
        when (event) {
            is WidgetEvent.Click -> {
                setClick(ProtoClickEvent.newBuilder().setEventId(event.eventId))
            }

            is WidgetEvent.TextChange -> {
                setTextChange(ProtoTextChangeEvent.newBuilder().setNewValue(event.newValue))
            }

            is WidgetEvent.Toggle -> {
                setToggle(ProtoToggleEvent.newBuilder().setChecked(event.checked))
            }

            is WidgetEvent.Selection -> {
                setSelection(
                    ProtoSelectionEvent
                        .newBuilder()
                        .setSelectedValue(event.value)
                        .setSelectedIndex(event.index),
                )
            }

            is WidgetEvent.Key -> {
                setKey(
                    ProtoKeyEvent
                        .newBuilder()
                        .setKeyCode(event.keyCode)
                        .setCtrl(event.ctrl)
                        .setAlt(event.alt)
                        .setShift(event.shift)
                        .setMeta(event.meta),
                )
            }

            is WidgetEvent.Scroll -> {
                setScroll(
                    ProtoScrollEvent
                        .newBuilder()
                        .setDeltaX(event.deltaX)
                        .setDeltaY(event.deltaY),
                )
            }

            is WidgetEvent.Focus -> {
                setFocus(ProtoFocusEvent.newBuilder().setHasFocus(event.hasFocus))
            }

            is WidgetEvent.Lifecycle -> {
                setLifecycle(ProtoLifecycleEvent.newBuilder().setLifecycleState(event.state))
            }
        }

    private fun toWidgetEvent(event: ProtoUIEvent): WidgetEvent? =
        when (event.eventCase) {
            ProtoUIEvent.EventCase.CLICK -> {
                WidgetEvent.Click(event.click.eventId)
            }

            ProtoUIEvent.EventCase.TEXT_CHANGE -> {
                WidgetEvent.TextChange(event.textChange.newValue)
            }

            ProtoUIEvent.EventCase.TOGGLE -> {
                WidgetEvent.Toggle(event.toggle.checked)
            }

            ProtoUIEvent.EventCase.SELECTION -> {
                WidgetEvent.Selection(event.selection.selectedValue, event.selection.selectedIndex)
            }

            ProtoUIEvent.EventCase.KEY -> {
                WidgetEvent.Key(
                    keyCode = event.key.keyCode,
                    ctrl = event.key.ctrl,
                    alt = event.key.alt,
                    shift = event.key.shift,
                    meta = event.key.meta,
                )
            }

            ProtoUIEvent.EventCase.SCROLL -> {
                WidgetEvent.Scroll(event.scroll.deltaX, event.scroll.deltaY)
            }

            ProtoUIEvent.EventCase.FOCUS -> {
                WidgetEvent.Focus(event.focus.hasFocus)
            }

            ProtoUIEvent.EventCase.LIFECYCLE -> {
                WidgetEvent.Lifecycle(event.lifecycle.lifecycleState)
            }

            ProtoUIEvent.EventCase.EVENT_NOT_SET, null -> {
                null
            }
        }
}
