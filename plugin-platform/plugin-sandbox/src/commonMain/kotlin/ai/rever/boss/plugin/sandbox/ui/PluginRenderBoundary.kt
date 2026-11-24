package ai.rever.boss.plugin.sandbox.ui

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.Layout
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException

/**
 * Contains exceptions thrown while *rendering* a plugin's subtree — during
 * measure, placement or draw — and attributes them to [pluginId] by position in
 * the tree rather than by inspecting stack frames.
 *
 * ### Why this exists
 *
 * [PluginCrashInterceptor] attributes a crash by looking for the plugin in the
 * stack: a sandbox thread name, a class whose name starts with the plugin id, or
 * a class loadable from the plugin's classloader. That works for exceptions
 * thrown *during composition*, which is what it was built for — a
 * `NoSuchMethodError` from a binary incompatibility has the plugin's own frames
 * on the stack.
 *
 * It cannot work for the layout phases. By the time Compose measures a subtree,
 * the plugin's composable functions have already run and returned; all they did
 * was emit `LayoutNode`s. Compose walks those nodes later from its own loop, so
 * the stack holds nothing but `androidx.compose.*` frames and nothing anywhere
 * records which plugin owns a node. Attribution returns null, and the window
 * handler in main.kt then treats it as a host crash — which disposes the window
 * and takes the whole app down.
 *
 * That is not hypothetical: BossConsole-Releases#16 was a bookmarks-plugin bug
 * (two LazyColumn items under one key, so one `LayoutNode` was measured twice in
 * a pass) that killed BossConsole with
 * `IllegalStateException: layout state is not idle before measure starts`, and
 * the reported stack trace contains not one plugin frame.
 *
 * This boundary sidesteps attribution entirely: whatever is thrown underneath it
 * belongs to the plugin it wraps, because of where it sits.
 *
 * ### What happens on a crash
 *
 * The throw is swallowed for the current frame, the subtree collapses to the
 * minimum size the parent allows, and [onRenderCrash] is invoked. That records
 * the crash, which tears the subtree down and rebuilds it so
 * [PluginErrorBoundary] can render its fallback — the same recovery path a
 * composition crash already takes.
 *
 * Reported once per boundary instance. A failing measure is usually retried
 * several times before the rebuild lands, and one broken panel should not
 * produce a burst of identical crash records.
 *
 * ### What it does not cover
 *
 * Compose can re-measure a dirty node *directly*, using its last constraints,
 * without walking down from its ancestors. When that node is inside the plugin
 * subtree this boundary is not on the stack and cannot intercept. The reported
 * crash did enter through the ancestors, and a size change from such a remeasure
 * propagates upward and re-enters here on the next pass — but the gap is real,
 * which is why the window handler must also stop treating an unattributed render
 * exception as fatal.
 *
 * Same family, equally unattributable, and equally not covered here:
 * `Modifier.onGloballyPositioned` / `onSizeChanged` callbacks, which run after
 * the `layout {}` block has returned and so outside its `try`, and pointer-input
 * and gesture handlers, which run on their own dispatch.
 *
 * ### Layout transparency, and where it stops
 *
 * Constraints are forwarded untouched and a healthy subtree measures exactly as
 * it would without this in the way. Two things an inserted `Layout` cannot make
 * transparent, invisible at the call site:
 *
 * - **Parent data does not cross it.** A `Modifier.weight()` or
 *   `Modifier.align()` applied inside [content] from a captured outer
 *   `RowScope`/`ColumnScope` now sits on a grandchild of the real parent and is
 *   ignored. No current caller does this; the next one might.
 * - **Multiple roots stack.** If [content] emits several siblings they are
 *   placed at the same origin with `Box` semantics, where previously the real
 *   parent would have laid them out.
 * - **Intrinsics go through the default policy.** Intrinsic measurements now
 *   resolve against `Layout`'s defaults rather than the child's own, which for a
 *   child with a cheap custom intrinsic — or an expensive real measure, such as a
 *   lazy list — is neither free nor guaranteed to give the same answer.
 *
 * @param pluginId Plugin that owns [content]; used only for logging here.
 * @param onRenderCrash Invoked at most once per boundary, on the UI thread rather
 *   than inline, so it is free to write snapshot state. It should not throw; if
 *   it does, the throw is logged and swallowed rather than allowed to escalate a
 *   contained crash.
 * @param dispatchToUiThread How that hand-off happens. Defaults to the EDT. A
 *   parameter rather than a hardcoded `SwingUtilities.invokeLater` because
 *   `runComposeUiTest` does not pump a real event queue, so a test that waited on
 *   the EDT would simply hang — and a boundary whose recovery path cannot be
 *   tested is not worth much.
 */
// Catching Throwable is this function's entire contract: a boundary that only
// caught the exception types it could predict would not be a boundary. The
// narrowing that does matter is [rethrowIfUncontainable], applied first at every
// catch site.
@Suppress("TooGenericExceptionCaught")
@Composable
internal fun PluginRenderBoundary(
    pluginId: String,
    onRenderCrash: (Throwable) -> Unit,
    dispatchToUiThread: (() -> Unit) -> Unit = ::defaultUiThreadDispatch,
    content: @Composable () -> Unit,
) {
    val logger = remember { BossLogger.forComponent("PluginRenderBoundary") }
    val reported = remember(pluginId) { AtomicBoolean(false) }

    // The callback is read through a remembered holder rather than captured
    // directly. Capturing it would make the measure lambda and draw modifier
    // below unstable, so Compose would hand Layout a fresh MeasurePolicy on every
    // recomposition — and LayoutNode.measurePolicy's setter compares by equality
    // and calls requestRemeasure() on change, forcing a full remeasure of the
    // plugin subtree each time. This is on the render path of every plugin panel
    // and status-bar widget, so that is worth avoiding.
    val currentOnRenderCrash = rememberUpdatedState(onRenderCrash)
    val currentDispatch = rememberUpdatedState(dispatchToUiThread)

    // Not a composable: called from measure, placement and draw, each of which has
    // already run rethrowIfUncontainable on the throwable.
    fun report(
        phase: String,
        error: Throwable,
    ) {
        if (!reported.compareAndSet(false, true)) return

        logger.error(
            LogCategory.UI,
            "Plugin crashed during $phase - containing it and rebuilding the panel",
            mapOf(
                "pluginId" to pluginId,
                "phase" to phase,
                "errorType" to error::class.simpleName.orEmpty(),
            ),
            error,
        )
        // Handed off to the EDT rather than called inline. This runs mid render
        // pass, and every useful thing a handler wants to do — set error state,
        // bump a key, record a crash — is a snapshot write that invalidates the
        // composition currently being laid out. Deferring here means no caller has
        // to know that: PluginErrorBoundary and PluginExtensionBoundary can both
        // just write state, instead of one deferring through
        // PluginCrashRegistry.recordCrash and the other not.
        currentDispatch.value {
            try {
                currentOnRenderCrash.value(error)
            } catch (secondary: Throwable) {
                secondary.rethrowIfUncontainable()
                // The handler failing must not turn a contained crash into a fatal one.
                logger.warn(
                    LogCategory.UI,
                    "Crash handler threw while containing a render crash",
                    mapOf("pluginId" to pluginId),
                    secondary,
                )
            }
        }
    }

    Layout(
        content = content,
        modifier =
            Modifier.drawWithContent {
                try {
                    drawContent()
                } catch (t: Throwable) {
                    t.rethrowIfUncontainable()
                    report("draw", t)
                }
            },
    ) { measurables, constraints ->
        // Constraints are forwarded untouched, so a subtree that is not crashing
        // lays out exactly as it would without this boundary in the way. Relaxing
        // the minimums here — the obvious way to make the error path collapse —
        // would silently change healthy plugins too: under a parent that passes a
        // non-zero minimum down (a Column(Modifier.fillMaxWidth()), say), a
        // wrap-content plugin root would stop stretching and start hugging its
        // content. The collapse belongs on the error path only, below.
        val placeables =
            try {
                measurables.map { it.measure(constraints) }
            } catch (t: Throwable) {
                t.rethrowIfUncontainable()
                report("measure", t)
                // Deliberately no children: measuring again this frame would just
                // hit the same throw. The rebuild triggered by onRenderCrash
                // replaces this subtree on a later frame.
                emptyList()
            }

        // No children — the error path — leaves this at the parent's minimum,
        // which is the smallest the boundary is allowed to be.
        val width = (placeables.maxOfOrNull { it.width } ?: 0).coerceIn(constraints.minWidth, constraints.maxWidth)
        val height = (placeables.maxOfOrNull { it.height } ?: 0).coerceIn(constraints.minHeight, constraints.maxHeight)

        layout(width, height) {
            try {
                placeables.forEach { it.place(0, 0) }
            } catch (t: Throwable) {
                t.rethrowIfUncontainable()
                report("placement", t)
            }
        }
    }
}

/**
 * Rethrow what a plugin boundary has no business containing.
 *
 * A cancelled composition is control flow, not a fault, and swallowing it would
 * strand whoever is awaiting the cancellation. An [OutOfMemoryError] says the JVM
 * is done; reporting it as "this plugin crashed" would be both wrong and a
 * distraction from the real problem.
 *
 * Deliberately not a `when` inside each catch block: pulled out here it stays one
 * decision in one place, applied identically by measure, placement, draw and the
 * crash handler itself.
 *
 * `internal` rather than private so it can be tested directly. Driving
 * cancellation through a real Compose runtime is not an option: throwing a
 * [CancellationException] out of measure tears down the composition's scope and
 * `runComposeUiTest` then waits for an idle state that never arrives, so the test
 * hangs rather than fails. The decision itself is pure, so it is tested as such.
 */
internal fun Throwable.rethrowIfUncontainable() {
    if (isUncontainable(this)) throw this
}

/**
 * Whether [error] is something no layer may contain.
 *
 * Public and shared so the boundary and the window handler cannot drift. They
 * did: the boundary rethrew [OutOfMemoryError] so a dead JVM was never blamed on
 * a plugin, but the window handler then caught it as an unattributed render
 * fault, contained it, and repainted every window — allocating more under heap
 * exhaustion, three times over, before anything escalated. The carve-out existed
 * only in the boundary's tests.
 *
 * [StackOverflowError] is included for the same reason: the stack is gone, and
 * repainting to recover would immediately blow it again.
 */
fun isUncontainable(error: Throwable): Boolean =
    error is CancellationException ||
        error is OutOfMemoryError ||
        error is StackOverflowError

/**
 * Default hand-off for [PluginRenderBoundary]: the AWT event dispatch thread.
 *
 * Matches how [PluginCrashRegistry.recordCrash] already defers its own state
 * writes, so a crash recorded through either route lands on the same thread.
 */
private fun defaultUiThreadDispatch(block: () -> Unit) = javax.swing.SwingUtilities.invokeLater(block)
