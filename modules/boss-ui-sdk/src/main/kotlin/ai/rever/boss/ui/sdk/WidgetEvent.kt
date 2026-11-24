package ai.rever.boss.ui.sdk

/**
 * A user interaction a renderer reports back to the plugin that owns the surface.
 *
 * One variant per `UIEvent` oneof case in `ui_protocol.proto`, so the mapping to the wire
 * ([UIEventMapper]) is total and a renderer cannot express an event the protocol can't carry.
 * Renderers previously reported events as a `(nodeId, eventType: String, eventData: String)` triple,
 * which silently lost the fields that don't fit one string (a selection's index, a key's modifiers)
 * and made "did anyone map this case?" invisible to the compiler.
 */
sealed interface WidgetEvent {
    /** A button press, or a click on a node with `clickable` set. */
    data class Click(
        val eventId: String,
    ) : WidgetEvent

    /** A text-field edit, carrying the full new value. */
    data class TextChange(
        val newValue: String,
    ) : WidgetEvent

    /** A checkbox or switch flip. */
    data class Toggle(
        val checked: Boolean,
    ) : WidgetEvent

    /** A dropdown selection: the chosen label and its index in the node's `options`. */
    data class Selection(
        val value: String,
        val index: Int,
    ) : WidgetEvent

    /**
     * A key press the surface received and nothing claimed.
     *
     * Surface-level, so it carries an empty node id: it arrives *because* neither the host keymap nor
     * the focused widget wanted it, which leaves no node to attribute it to. [keyCode] is the platform
     * key code (the AWT `VK_` constant on the JVM host). Key **down** only — the wire type has no
     * up/down discriminator, so both edges would read as two presses.
     */
    data class Key(
        val keyCode: Int,
        val ctrl: Boolean = false,
        val alt: Boolean = false,
        val shift: Boolean = false,
        val meta: Boolean = false,
    ) : WidgetEvent

    /** A scroll gesture delta, in logical pixels. */
    data class Scroll(
        val deltaX: Float,
        val deltaY: Float,
    ) : WidgetEvent

    /** Focus gained ([hasFocus] `true`) or lost. */
    data class Focus(
        val hasFocus: Boolean,
    ) : WidgetEvent

    /**
     * A surface lifecycle transition. [state] is one of [LifecycleStates]; it stays a free string
     * because the proto field is one, and an unknown state must survive the round trip rather than
     * being dropped.
     */
    data class Lifecycle(
        val state: String,
    ) : WidgetEvent
}

/** The lifecycle states `ui_protocol.proto` documents for `LifecycleEvent.lifecycle_state`. */
object LifecycleStates {
    const val CREATED: String = "created"
    const val RESUMED: String = "resumed"
    const val PAUSED: String = "paused"
    const val DESTROYED: String = "destroyed"
}

/**
 * A [WidgetEvent] tagged with the node that produced it.
 *
 * Surface-level events ([WidgetEvent.Lifecycle], and [WidgetEvent.Key] once no widget has claimed the
 * press) carry an empty [nodeId] — they belong to the surface, not to a node in its tree.
 */
data class EmittedEvent(
    val nodeId: String,
    val event: WidgetEvent,
)
