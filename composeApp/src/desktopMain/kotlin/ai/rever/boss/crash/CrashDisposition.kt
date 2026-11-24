package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.ui.isUncontainable

/**
 * What the crash dialog's exits should actually do.
 *
 * Every exit from that dialog used to terminate the process — dismiss, Escape,
 * and even a *successful* report submission. That is the right answer for a
 * corrupt host and the wrong one for the common case: a dynamic plugin threw
 * from a callback the host invoked, and the fix is to drop that plugin, not the
 * user's windows, tabs and terminal sessions.
 */
sealed interface CrashDisposition {
    /**
     * Attributable to [pluginId] and recoverable by disabling it. The dialog's
     * primary action continues without the plugin instead of terminating.
     */
    data class RecoverablePlugin(
        val pluginId: String,
    ) : CrashDisposition

    /** The host itself is broken (or recovery is impossible); terminate as before. */
    data object FatalHost : CrashDisposition
}

/**
 * Classify a crash.
 *
 * The order is the argument:
 *
 * 1. **No plugin id** — nobody to disable. Host fault, terminate.
 * 2. **[isUncontainable]** — [OutOfMemoryError] and [StackOverflowError] are not
 *    a plugin's to give back. Disabling the plugin frees nothing at the moment
 *    the heap is already exhausted, and every step of recovery (closing tabs,
 *    unloading, rendering a toast) allocates. The render path carves these out
 *    for the same reason; see `decideWindowExceptionRoute`.
 * 3. **No recovery wired** ([recoveryAvailable] false) — headless, or a crash
 *    before the plugin layer exists. Pretending we disabled something would
 *    leave the crashing plugin live and the dialog dismissed.
 * 4. Otherwise recoverable.
 *
 * Deliberately pure so the matrix is testable without a plugin manager, a
 * window, or a JVM willing to be exited.
 */
fun classifyCrash(
    throwable: Throwable,
    pluginId: String?,
    recoveryAvailable: Boolean,
): CrashDisposition =
    when {
        pluginId.isNullOrBlank() -> CrashDisposition.FatalHost
        throwable.hasUncontainableCause() -> CrashDisposition.FatalHost
        !recoveryAvailable -> CrashDisposition.FatalHost
        else -> CrashDisposition.RecoverablePlugin(pluginId)
    }

/**
 * [isUncontainable] applied to the whole cause chain, not just the top.
 *
 * The flat check was not enough for the way these actually arrive. Attribution
 * deliberately walks the causes because wrapping is routine, so an
 * `OutOfMemoryError` reaching here as `InvocationTargetException(cause = OOM)` got
 * tagged with a plugin id, passed a top-level `is` check, and classified as
 * recoverable. Recovery would then allocate a status message, launch a coroutine
 * and tear down tabs across every window on an already-exhausted heap, which is
 * the exact thing the carve-out exists to prevent.
 *
 * Shared with [decideWindowExceptionRoute] through [chainOfCauses], because the two
 * carve-outs are worthless unless they agree - and for one round they did not.
 */
internal fun Throwable.hasUncontainableCause(): Boolean = chainOfCauses().any { isUncontainable(it) }

/**
 * Fatal to the *process*, as opposed to merely uncontainable by a repaint.
 *
 * A strict subset of [hasUncontainableCause], and the distinction earns its keep
 * in [decideWindowExceptionRoute]. Both an [OutOfMemoryError] and a
 * [StackOverflowError] are uncontainable there, but for different reasons and
 * with different remedies: after an OOM the heap is gone and every recovery path
 * — a status message, a coroutine, a repaint of every window — allocates on a
 * heap that has nothing left, so there is nothing to do but escalate. After a
 * stack overflow the stack has already unwound and the process is healthy; the
 * only thing you must not do is re-enter whatever recursed. Quarantining the
 * plugin that recursed does exactly that, and keeps the session.
 *
 * Kept narrow on purpose. Anything added here becomes a reason to end the app.
 */
internal fun Throwable.hasFatalCause(): Boolean = chainOfCauses().any { it is OutOfMemoryError }
