package ai.rever.boss.ui.sdk

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scroll throttling policy, on a virtual clock.
 *
 * `ScrollEvent` was in the proto from the start and emitted by nobody, because an unthrottled scroll is
 * one IPC message per frame. Throttling is easy; throttling *without losing where the user ended up* is
 * the part that is easy to get wrong, and it is the part these tests exist for — the standard throttle
 * (`Flow.sample`) satisfies every "it coalesced" assertion and silently drops the tail of a fling,
 * leaving the plugin permanently out of step with the screen and nothing later to correct it.
 *
 * So the invariant asserted throughout is the strong one: **the emitted deltas sum to the total
 * displacement**. A plugin accumulating them is never wrong, only coarser.
 */
class ScrollCoalescerTest {
    @Test
    fun `the first offset seeds the baseline without reporting a scroll`() =
        runTest {
            // Where the container already is, not a movement. A surface that renders a scroll node and is
            // never touched must put nothing on the wire.
            val events = ScrollCoalescer.coalesce(offsetsOf(120f), WINDOW).toList()

            assertEquals(emptyList(), events, "a single starting position is not a scroll")
        }

    @Test
    fun `an offset that has not moved reports nothing`() =
        runTest {
            // snapshotFlow re-reports on recomposition; an unchanged position must not become traffic.
            val events = ScrollCoalescer.coalesce(offsetsOf(0f, 0f, 0f), WINDOW).toList()

            assertEquals(emptyList(), events, "a repeated position is not a scroll")
        }

    @Test
    fun `the first movement is reported without waiting for the window`() =
        runTest {
            // Leading edge. A single nudge — a wheel click, a keyboard scroll — must not sit for a window
            // before the plugin hears about it, which is why the window is applied after the emit and not
            // before it. Sampled at emission, because collecting to a list also runs the trailing window.
            val emittedAt = mutableListOf<Long>()

            val events =
                ScrollCoalescer
                    .coalesce(offsetsOf(0f, 40f), WINDOW)
                    .onEach { emittedAt += testScheduler.currentTime }
                    .toList()

            assertEquals(listOf(WidgetEvent.Scroll(0f, 40f)), events)
            assertEquals(listOf(0L), emittedAt, "the leading edge must not be delayed by a window")
        }

    @Test
    fun `a burst arriving faster than the window collapses into a couple of events`() =
        runTest {
            // The pathological shape: a hundred offsets with no gaps at all. Wired straight through, that
            // is a hundred cross-process messages for one gesture.
            val events = ScrollCoalescer.coalesce(offsetsOf(*ramp(101)), WINDOW).toList()

            assertTrue(events.size <= 3, "a gapless burst must collapse, got ${events.size}: $events")
            assertEquals(100f, events.totalY(), "no displacement may be lost to coalescing")
        }

    @Test
    fun `a fling emits roughly one event per window and lands on the resting position`() =
        runTest {
            // 60 frames at ~60Hz: what a real fling looks like. Sixty messages unthrottled; the window is
            // 60ms, so this should cost about a sixth of that.
            val offsets =
                flow {
                    repeat(FRAMES) { frame ->
                        emit(ScrollOffset(0f, frame.toFloat()))
                        delay(FRAME_MS)
                    }
                }

            val events = ScrollCoalescer.coalesce(offsets, WINDOW).toList()

            val expectedWindows = FRAMES * FRAME_MS / WINDOW
            assertTrue(events.size < FRAMES, "a fling must not produce one event per frame")
            assertTrue(
                events.size <= expectedWindows + 3,
                "expected about $expectedWindows events for a $FRAMES-frame fling, got ${events.size}",
            )
            // The whole point. `sample()` passes both assertions above and fails this one, because the
            // tail of the gesture never reaches a tick.
            assertEquals(
                (FRAMES - 1).toFloat(),
                events.totalY(),
                "the accumulated deltas must land the plugin exactly where the user stopped",
            )
        }

    @Test
    fun `a burst that stops mid-window still delivers its final position`() =
        runTest {
            // The specific regression, isolated: movement inside a window and then silence. Anything that
            // waits for the next tick to publish never publishes, because a scroll that has stopped emits
            // nothing more to trigger one.
            val offsets =
                flow {
                    emit(ScrollOffset(0f, 0f))
                    emit(ScrollOffset(0f, 10f))
                    delay(WINDOW / 4)
                    emit(ScrollOffset(0f, 11f))
                    delay(WINDOW / 4)
                    emit(ScrollOffset(0f, 12f))
                    // …and the user lifts their fingers here, well inside the window.
                }

            val events = ScrollCoalescer.coalesce(offsets, WINDOW).toList()

            assertEquals(
                12f,
                events.totalY(),
                "the resting position must arrive even though the gesture ended mid-window",
            )
            assertEquals(
                WidgetEvent.Scroll(0f, 2f),
                events.last(),
                "the trailing event carries exactly the movement the earlier ones did not",
            )
        }

    @Test
    fun `horizontal and vertical deltas are reported independently`() =
        runTest {
            val offsets = flowOf(ScrollOffset(0f, 0f), ScrollOffset(30f, -12f))

            val events = ScrollCoalescer.coalesce(offsets, WINDOW).toList()

            assertEquals(listOf(WidgetEvent.Scroll(30f, -12f)), events)
        }

    private fun offsetsOf(vararg y: Float) = flowOf(*y.map { ScrollOffset(0f, it) }.toTypedArray())

    private fun ramp(count: Int): FloatArray = FloatArray(count) { it.toFloat() }

    private fun List<WidgetEvent.Scroll>.totalY(): Float = sumOf { it.deltaY.toDouble() }.toFloat()

    private companion object {
        const val WINDOW = 60L
        const val FRAME_MS = 16L
        const val FRAMES = 60
    }
}
