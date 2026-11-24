package ai.rever.boss.ui.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow

/**
 * A scroll container's absolute offset, in **layout pixels** — Compose's `ScrollState.value`, not
 * density-independent units. Stated because the Rust renderer mirrors this spec and the two differ on
 * a HiDPI display.
 *
 * Absolute rather than a delta on purpose: `ScrollEvent` is a delta on the wire, but *deriving* those
 * deltas from positions is what makes coalescing lossless. Two positions always yield the one delta
 * between them however many frames were skipped, whereas dropping a delta destroys information that
 * cannot be recovered.
 */
data class ScrollOffset(
    val x: Float,
    val y: Float,
)

/**
 * Turns a scroll container's per-frame offset stream into the handful of `ScrollEvent`s worth putting
 * on an IPC stream.
 *
 * `ui_protocol.proto` has carried `ScrollEvent` since the beginning and nothing ever raised one, for a
 * concrete reason: a fling emits one offset per frame, so wiring a scroll position straight to the wire
 * is one cross-process message every ~16ms for the duration of the gesture, per scrolling surface. That
 * is the throttling policy #34 deferred this family for.
 *
 * ## The policy
 *
 * At most one event per [WINDOW_MS], **and the resting position always arrives**. Those are two
 * requirements, and the second is the one that is easy to get wrong: the obvious throttle
 * (`Flow.sample`) samples on a timer and simply does not emit the tail of a burst, so a fling leaves
 * the plugin holding whatever offset happened to be current at the last tick — permanently wrong, with
 * no later event to correct it, because a scroll that has stopped produces nothing more.
 *
 * ## How it holds
 *
 * [conflate] plus a [delay] in the collector, rather than a timer:
 *
 * - conflation keeps the **newest** pending offset and discards the ones it superseded, which is
 *   exactly coalescing — and it cannot drop the last one, because the last one is by definition never
 *   superseded;
 * - the collector's `delay` is the window, and it is applied *after* emitting, so the first movement of
 *   a gesture goes out immediately instead of waiting a frame budget;
 * - upstream completion cannot outrun the collector: a conflated channel closes gracefully, so the
 *   buffered value is delivered before the flow ends.
 *
 * The result is an invariant a test can hold onto, and a stronger one than "the last event arrives":
 * **the emitted deltas sum to the total displacement**, whatever the burst shape. A plugin that
 * accumulates them is never out of step with what the user sees — it just learns about the middle of a
 * fling in fewer, larger steps.
 *
 * The invariant is scoped to **one continuous collection**, which is worth being exact about because in
 * production the upstream never completes and the flow only ever ends by cancellation. A collector
 * cancelled mid-window discards the pending offset, and a restart (a tree update replacing the node, so
 * a fresh `ScrollState` at zero) silently re-seeds its baseline. Both are teardowns from the plugin's
 * point of view, and the proto tells plugins to treat them as a reset rather than to keep accumulating
 * across one.
 *
 * No timers, no shared mutable state across coroutines, and nothing to leak when the surface goes away:
 * the flow is cold and dies with its collector.
 */
object ScrollCoalescer {
    /**
     * The coalescing window.
     *
     * ~4 frames at 60Hz. Small enough that a plugin driving a scrollbar or a "scrolled to bottom" check
     * still feels live, large enough that a one-second fling costs ~16 messages instead of ~60.
     */
    const val WINDOW_MS: Long = 60L

    /**
     * Coalesce [offsets] into scroll deltas.
     *
     * The first offset seeds the baseline and produces **no** event: it is where the container already
     * is, not a movement. An offset equal to the current baseline produces no event either, so a
     * recomposition that re-reports the same position stays off the wire.
     *
     * @param windowMs minimum spacing between emissions. A parameter only so tests can pick a window
     *   they can reason about; production uses [WINDOW_MS].
     */
    fun coalesce(
        offsets: Flow<ScrollOffset>,
        windowMs: Long = WINDOW_MS,
    ): Flow<WidgetEvent.Scroll> =
        flow {
            var baseline: ScrollOffset? = null
            offsets.conflate().collect { offset ->
                val from = baseline
                baseline = offset
                if (from == null || from == offset) return@collect
                emit(WidgetEvent.Scroll(deltaX = offset.x - from.x, deltaY = offset.y - from.y))
                // After the emit, not before: the leading edge of a gesture is the part a plugin most
                // wants promptly, and delaying first would add a window's latency to every scroll that
                // is only one nudge long. While this suspends, upstream conflates into one pending value.
                delay(windowMs)
            }
        }
}
