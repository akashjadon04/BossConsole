package ai.rever.boss.plugin.sandbox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers containment of plugin crashes in the render phases.
 *
 * The failure being guarded against: a plugin's composables have already
 * returned by the time Compose measures, places or draws their nodes, so an
 * exception there carries no plugin frame and
 * [PluginCrashInterceptor.attributeToPlugin] cannot identify an owner. The
 * window handler then treats it as a host fault and disposes the window, ending
 * the app — which is how a duplicate LazyColumn key in the bookmarks plugin took
 * BossConsole down (BossConsole-Releases#16).
 */
// The boundary under test exists to catch anything, so the tests must be able to
// throw and observe anything — including Errors.
@Suppress("TooGenericExceptionCaught")
@OptIn(ExperimentalTestApi::class)
class PluginRenderBoundaryTest {
    private companion object {
        const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.test"

        /**
         * Run the boundary's crash hand-off inline instead of on the EDT.
         *
         * `runComposeUiTest` does not pump a real event queue, so anything posted
         * with `invokeLater` never runs and a test that waited for it would hang.
         * Production still defers — that is what makes the callback safe to write
         * snapshot state from — and this only changes *when*, not *what*.
         */
        val runInline: (() -> Unit) -> Unit = { it() }
    }

    /** Throws while being measured — the phase from the real crash report. */
    @Composable
    private fun ThrowsDuringMeasure(error: Throwable) {
        Layout(content = {}) { _, _ -> throw error }
    }

    /** Throws while placing its children. */
    @Composable
    private fun ThrowsDuringPlacement(error: Throwable) {
        Layout(content = {}) { _, _ ->
            layout(10, 10) { throw error }
        }
    }

    /** Throws while drawing. */
    @Composable
    private fun ThrowsDuringDraw(error: Throwable) {
        Box(
            Modifier
                .size(10.dp)
                .drawWithContent { throw error },
        )
    }

    private fun captureCrashFrom(content: @Composable () -> Unit): Throwable? {
        val captured = AtomicReference<Throwable?>(null)
        runComposeUiTest {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    PluginRenderBoundary(
                        pluginId = PLUGIN_ID,
                        onRenderCrash = { captured.set(it) },
                        dispatchToUiThread = runInline,
                        content = content,
                    )
                }
            }
        }
        return captured.get()
    }

    @Test
    fun `a crash during measure is contained and handed to the owner`() {
        val boom = IllegalStateException("layout state is not idle before measure starts")

        val captured = captureCrashFrom { ThrowsDuringMeasure(boom) }

        assertNotNull(captured, "the measure crash escaped the boundary")
        assertSame(boom, captured, "the owner was handed the wrong throwable")
    }

    @Test
    fun `a crash during placement is contained`() {
        val boom = IllegalStateException("placement blew up")

        val captured = captureCrashFrom { ThrowsDuringPlacement(boom) }

        assertNotNull(captured, "the placement crash escaped the boundary")
        assertSame(boom, captured)
    }

    @Test
    fun `a crash during draw is contained`() {
        val boom = IllegalStateException("draw blew up")

        val captured = captureCrashFrom { ThrowsDuringDraw(boom) }

        assertNotNull(captured, "the draw crash escaped the boundary")
        assertSame(boom, captured)
    }

    @Test
    fun `the reported duplicate-LazyColumn-key crash is contained and a sibling still renders`() {
        // The actual shape from BossConsole-Releases#16: two items under one key
        // share a subcomposition slot and therefore one LayoutNode, so the list
        // measures that node twice in a pass. Reproduced rather than described, so
        // the boundary is proven against the real failure and not a synthetic one.
        val captured = AtomicReference<Throwable?>(null)
        // Set from a draw modifier, not from composition: composition always
        // completes before measure, so a flag set there would be true whether or
        // not the crash took the render pass down. Drawing is downstream of the
        // crash and is the thing that actually has to survive.
        val siblingDrew = AtomicBoolean(false)

        runComposeUiTest {
            setContent {
                Column(Modifier.fillMaxSize()) {
                    PluginRenderBoundary(
                        pluginId = "ai.rever.boss.plugin.dynamic.bookmarks",
                        onRenderCrash = { captured.set(it) },
                        dispatchToUiThread = runInline,
                    ) {
                        LazyColumn(Modifier.size(40.dp)) {
                            items(listOf("dup", "dup"), key = { it }) {
                                Box(Modifier.size(20.dp))
                            }
                        }
                    }
                    Box(
                        Modifier
                            .size(10.dp)
                            .drawWithContent {
                                siblingDrew.set(true)
                                drawContent()
                            },
                    )
                }
            }
        }

        assertNotNull(
            captured.get(),
            "duplicate keys did not throw here - if Compose stopped treating this as an error " +
                "this test needs rewriting, but the boundary is still what contains it",
        )
        assertTrue(siblingDrew.get(), "the rest of the window stopped rendering when the plugin crashed")
    }

    @Test
    fun `content that renders cleanly is untouched`() {
        val captured = AtomicReference<Throwable?>(null)
        val childDrew = AtomicBoolean(false)

        runComposeUiTest {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    PluginRenderBoundary(
                        pluginId = PLUGIN_ID,
                        onRenderCrash = { captured.set(it) },
                        dispatchToUiThread = runInline,
                    ) {
                        Box(
                            Modifier
                                .size(24.dp)
                                .drawWithContent {
                                    childDrew.set(true)
                                    drawContent()
                                },
                        )
                    }
                }
            }
        }

        assertNull(captured.get(), "a healthy subtree must not report a crash")
        assertTrue(childDrew.get(), "the boundary must not stop its content rendering")
    }

    @Test
    fun `a repeatedly failing subtree is reported once`() {
        // A failing measure is retried before the rebuild lands. One broken panel
        // must not produce a burst of identical crash records.
        val reports = AtomicInteger(0)

        runComposeUiTest {
            setContent {
                Box(Modifier.fillMaxSize()) {
                    PluginRenderBoundary(
                        pluginId = PLUGIN_ID,
                        onRenderCrash = { reports.incrementAndGet() },
                        dispatchToUiThread = runInline,
                    ) {
                        ThrowsDuringMeasure(IllegalStateException("still broken"))
                    }
                }
            }
        }

        assertEquals(1, reports.get(), "the boundary reported the same crash more than once")
    }

    @Test
    fun `a throwing crash handler does not escalate the contained crash`() {
        // Documented guarantee: the handler failing must not turn a crash the
        // boundary already contained into a fatal one.
        var escaped: Throwable? = null

        try {
            runComposeUiTest {
                setContent {
                    PluginRenderBoundary(
                        pluginId = PLUGIN_ID,
                        onRenderCrash = { throw IllegalStateException("the handler itself is broken") },
                        dispatchToUiThread = runInline,
                    ) {
                        ThrowsDuringMeasure(IllegalStateException("measure blew up"))
                    }
                }
            }
        } catch (t: Throwable) {
            escaped = t
        }

        assertNull(escaped, "a throwing crash handler escalated a contained crash")
    }

    @Test
    fun `an OutOfMemoryError is not swallowed`() {
        // A dead JVM is not the plugin's to own, and pretending otherwise would
        // turn an unrecoverable condition into a silent one.
        val fatal = OutOfMemoryError("Java heap space")
        val captured = AtomicReference<Throwable?>(null)
        var escaped: Throwable? = null

        try {
            runComposeUiTest {
                setContent {
                    PluginRenderBoundary(
                        pluginId = PLUGIN_ID,
                        onRenderCrash = { captured.set(it) },
                        dispatchToUiThread = runInline,
                    ) {
                        ThrowsDuringMeasure(fatal)
                    }
                }
            }
        } catch (t: Throwable) {
            escaped = t
        }

        assertNull(captured.get(), "a fatal JVM error must not be reported as a plugin crash")
        assertNotNull(escaped, "an OutOfMemoryError must propagate, not be contained")
        assertTrue(
            generateSequence(escaped) { it.cause }.any { it === fatal },
            "something else failed - the OutOfMemoryError itself did not propagate",
        )
    }

    @Test
    fun `a healthy subtree measures the same with the boundary as without it`() {
        // The riskiest claim in this change: the boundary is layout-transparent
        // for content that is not crashing. Relaxing the child's minimum
        // constraints — the obvious way to make the error path collapse — would
        // silently reshape every plugin panel, so it is asserted rather than
        // commented. Both cases run under a parent that passes a non-zero minimum
        // width down, which is exactly where the difference would show.
        val withBoundary = AtomicReference<IntSize?>(null)
        val without = AtomicReference<IntSize?>(null)

        runComposeUiTest {
            setContent {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth()) {
                        PluginRenderBoundary(
                            pluginId = PLUGIN_ID,
                            onRenderCrash = { },
                            dispatchToUiThread = runInline,
                        ) {
                            Box(Modifier.size(24.dp).onSizeChanged { withBoundary.set(it) })
                        }
                    }
                    Column(Modifier.fillMaxWidth()) {
                        Box(Modifier.size(24.dp).onSizeChanged { without.set(it) })
                    }
                }
            }
        }

        assertNotNull(withBoundary.get(), "the wrapped child never measured")
        assertEquals(
            without.get(),
            withBoundary.get(),
            "the boundary changed the size of healthy content",
        )
    }

    @Test
    fun `a wrap-content child is not stretched by the boundary`() {
        // The specific regression the constraint forwarding prevents: under a
        // parent with a non-zero minimum, a relaxed child would hug its content
        // where it used to stretch, or vice versa.
        val measured = AtomicReference<IntSize?>(null)

        runComposeUiTest {
            setContent {
                Column(Modifier.fillMaxWidth()) {
                    PluginRenderBoundary(
                        pluginId = PLUGIN_ID,
                        onRenderCrash = { },
                        dispatchToUiThread = runInline,
                    ) {
                        Box(Modifier.fillMaxWidth().height(10.dp).onSizeChanged { measured.set(it) })
                    }
                }
            }
        }

        val size = measured.get()
        assertNotNull(size, "the child never measured")
        assertTrue(
            size.width > 0,
            "a fillMaxWidth child under the boundary collapsed instead of filling",
        )
    }

    @Test
    fun `rethrowIfUncontainable lets a plugin fault through and stops the rest`() {
        // Tested directly rather than through the Compose harness: throwing a
        // CancellationException out of measure tears down the composition scope,
        // and runComposeUiTest then waits for an idle state that never comes, so
        // such a test hangs instead of failing. The decision is pure logic, and
        // the end-to-end propagation half is covered by the OutOfMemoryError test
        // above.
        val ordinary = IllegalStateException("a plugin laid itself out wrong")
        // Must not throw — an ordinary fault is exactly what the boundary contains.
        ordinary.rethrowIfUncontainable()

        val cancelled = CancellationException("composition cancelled")
        val cancellationEscaped =
            try {
                cancelled.rethrowIfUncontainable()
                null
            } catch (t: Throwable) {
                t
            }
        assertSame(cancelled, cancellationEscaped, "cancellation is control flow and must propagate")

        val fatal = OutOfMemoryError("Java heap space")
        val fatalEscaped =
            try {
                fatal.rethrowIfUncontainable()
                null
            } catch (t: Throwable) {
                t
            }
        assertSame(fatal, fatalEscaped, "a dead JVM must not be reported as a plugin crash")
    }
}
