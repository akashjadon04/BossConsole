package ai.rever.boss.crash

import ai.rever.boss.plugin.sandbox.ui.PluginRecoveryQuarantine
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The lookup the host installs into [ai.rever.boss.plugin.sandbox.PluginExecutionBoundary].
 *
 * A named function rather than a lambda at the call site, so a test can hold the
 * same object production installs. Written inline, the only thing pinning it was
 * that `main` happened to call it - delete that line and every test still passes
 * while attribution silently reverts to the spoofable duck-typed fallback. That is
 * the same unpinned-wiring bug this change set exists to close, one layer up.
 */
internal fun hostPluginIdResolver(): (ClassLoader) -> String? =
    { loader -> (loader as? ai.rever.boss.plugin.loader.PluginClassLoader)?.pluginId }

/**
 * A plugin id fit to drop into a sentence a user reads.
 *
 * The id comes from a plugin-supplied manifest and every display site shares one
 * status-bar slot, so a long or control-character-bearing id pushes the rest of
 * the sentence - including where to undo this - out of view. Falls back to a
 * placeholder rather than rendering `Plugin '' crashed` when nothing survives the
 * filter.
 */
internal fun displayPluginId(pluginId: String): String {
    val flattened = pluginId.filter { it.isLetterOrDigit() || it in ".-_:" }
    return when {
        flattened.isBlank() -> "unknown plugin"
        flattened.length <= MAX_DISPLAYED_ID -> flattened
        else -> flattened.take(MAX_DISPLAYED_ID) + "..."
    }
}

/** Comfortably fits the real ids (`ai.rever.boss.plugin.dynamic.terminaltab` is 43). */
private const val MAX_DISPLAYED_ID = 60

/**
 * Takes a crashed plugin out of the running app so the app can keep running.
 *
 * @return true when recovery took effect. False means the crash could not be
 *   contained after all, and the caller must fall back to terminating — see
 *   [CrashHandler.resolveCrash]. Returning true on a no-op would be the worst
 *   outcome available: dialog dismissed, crashing plugin still live.
 */
fun interface PluginCrashRecoveryHandler {
    fun recover(
        pluginId: String,
        error: Throwable,
    ): Boolean

    /**
     * Whether [pluginId] is something this handler could actually take out.
     *
     * Asked at *classification* time, so the dialog cannot promise a recovery the
     * exit will not deliver. Before this existed, classification only asked whether
     * a handler was wired at all: a crash attributed to a plugin no live manager
     * knows about - a lingering thread from something already unloaded, or a
     * classloader-scan attribution after unload - rendered "BOSS keeps running, x
     * will be disabled", and then terminated the moment the user clicked Continue
     * Without Plugin.
     *
     * Defaults to true so a simple lambda handler still satisfies the interface;
     * the real coordinator answers from its steps.
     */
    fun canRecover(pluginId: String): Boolean = true
}

/**
 * Process-wide seam between the crash handler and the plugin layer.
 *
 * [CrashHandler] lives below the plugin manager and cannot reach it (managers are
 * per-window and created inside `DefaultPlugin`), so the desktop layer installs a
 * handler once at startup — the same shape as
 * `DynamicPluginManager.pluginUiTeardown`. Null in tests and headless runs, which
 * is exactly what makes such a crash classify as [CrashDisposition.FatalHost]
 * rather than silently pretend to recover.
 */
object PluginCrashRecovery {
    private val logger = BossLogger.forComponent("PluginCrashRecovery")

    private val installed =
        java.util.concurrent.atomic
            .AtomicReference<PluginCrashRecoveryHandler?>(null)

    var handler: PluginCrashRecoveryHandler?
        get() = installed.get()
        set(value) = installed.set(value)

    /**
     * Install [build]'s result only if nothing is installed yet.
     *
     * The desktop wiring runs once per window, and a plain `if (handler == null)`
     * let two windows opening together both construct a coordinator. Harmless -
     * they are equivalent - but a compare-and-set states the intent instead of
     * relying on it.
     */
    fun installIfAbsent(build: () -> PluginCrashRecoveryHandler) {
        // What this guarantees: exactly one handler is ever *installed*, and once
        // installed it is never replaced. Not that build() runs once - updateAndGet
        // re-runs its function on CAS contention, so a race can still construct a
        // coordinator that is then discarded. That is fine because construction is
        // pure; claiming otherwise is what the previous two versions of this comment
        // got wrong.
        installed.updateAndGet { existing -> existing ?: build() }
    }

    /**
     * Whether [pluginId] could actually be recovered right now.
     *
     * Both halves matter: a handler has to be wired (false in headless runs and
     * before the plugin layer exists), and it has to recognise this plugin. Asking
     * only the first is what let the dialog promise something the exit could not
     * do.
     */
    @Suppress("TooGenericExceptionCaught")
    fun canRecover(pluginId: String?): Boolean {
        val active = handler
        val id = pluginId?.takeIf { it.isNotBlank() }
        if (active == null || id == null) return false
        // Never throws: this decides what a crash dialog says, and a failure here
        // must fall back to the honest "we terminate" answer rather than propagate.
        return try {
            active.canRecover(id)
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "Could not decide whether a plugin is recoverable - treating the crash as fatal",
                mapOf("pluginId" to id),
                t,
            )
            false
        }
    }

    /**
     * Recover [pluginId], or report that we could not.
     *
     * Never throws: this runs on the way out of a crash dialog, where a second
     * failure has nowhere to go.
     */
    // Throwable: recovery reaches into the plugin layer, where a half-unloaded
    // classloader throws NoClassDefFoundError rather than an Exception - and a
    // failure to recover has to become a termination, not a second crash.
    @Suppress("TooGenericExceptionCaught")
    fun recover(
        pluginId: String,
        error: Throwable,
    ): Boolean {
        val active = handler ?: return false
        return try {
            active.recover(pluginId, error)
        } catch (t: Throwable) {
            logger.error(
                LogCategory.SYSTEM,
                "Plugin crash recovery failed",
                mapOf("pluginId" to pluginId),
                t,
            )
            false
        }
    }
}

/**
 * The plugin-layer operations recovery needs, as a seam.
 *
 * An interface rather than a `DynamicPluginManager`, because the manager is
 * per-window, built inside `DefaultPlugin`, and needs a live UI - none of which a
 * test of "does dismissing a plugin crash kill the app" should have to construct.
 * The desktop wiring in `PluginLoaderDelegateSetup` supplies the real ones.
 */
interface PluginRecoverySteps {
    /** Whether this id names a plugin anything can actually act on. */
    fun isKnown(pluginId: String): Boolean

    /**
     * Mark the plugin crashed, so every window's error boundary swaps to its
     * fallback and stops rendering plugin content. Must take effect before the
     * crash dialog goes away.
     */
    fun quarantine(
        pluginId: String,
        error: Throwable,
    )

    /** Close the plugin's open tabs across every window. */
    suspend fun closeTabs(pluginId: String)

    /** Disable it in every live manager; false if none accepted it. */
    suspend fun disable(pluginId: String): Boolean

    /**
     * Record the disable, so a crash-on-load plugin does not return next launch.
     *
     * @return whether anything was actually written. False is a real outcome, not
     *   an error: the plugin may not be installed and have no known jar, in which
     *   case nothing can be recorded and the user needs to hear about it.
     */
    fun persistDisabled(pluginId: String): Boolean

    /** Tell the user which plugin is going away and how to get it back. */
    fun notifyDisabling(pluginId: String)

    /**
     * Tell the user the unload did not fully take.
     *
     * The first notice fires before any of the unload has run, because the user is
     * looking at the app the moment the dialog closes and deserves to know why a
     * plugin just vanished. When `disableEverywhere` finds no live manager, or the
     * disable cannot be persisted, the plugin is quarantined in memory only and
     * will be back at the next launch - the opposite of what they were told. This
     * is the correction.
     */
    fun notifyDisableIncomplete(pluginId: String)
}

/**
 * The real recovery: quarantine now, unload in the background.
 *
 * ### Why the split between synchronous and background work
 *
 * The synchronous half is what makes the app *safe*: the plugin is recorded as
 * crashed before this returns, because the caller disposes the crash dialog
 * immediately afterwards and the user is looking at the app again.
 *
 * Precisely: `recordRenderFault` writes the thread-safe map synchronously, so
 * `hasCrashed` is true the moment this returns - which is what
 * `CrashHandler.isSuppressedByQuarantine` reads. The Compose-observable state
 * that actually swaps every window's
 * [ai.rever.boss.plugin.sandbox.ui.PluginErrorBoundary] to its fallback is flipped
 * on the next EDT cycle via `invokeLater`, so the *visible* swap lands a frame
 * later. Both are fine here; the ordering guarantee rests on the map, not on the
 * repaint.
 *
 * Unloading is the slow half — closing tabs across every window on the EDT,
 * stopping sandboxes, rewriting `installed.json` — and it is launched rather than
 * awaited. Blocking the event thread on it would freeze the app the user was just
 * promised would keep working, and a failure there is not a reason to kill the
 * session: the plugin is already quarantined and rendering nothing.
 */
class PluginCrashRecoveryCoordinator(
    private val scope: CoroutineScope,
    private val steps: PluginRecoverySteps,
) : PluginCrashRecoveryHandler {
    private val logger = BossLogger.forComponent("PluginCrashRecovery")

    /** The same question [isActionable] asks, asked early enough to change the wording. */
    override fun canRecover(pluginId: String): Boolean = steps.isKnown(pluginId)

    override fun recover(
        pluginId: String,
        error: Throwable,
    ): Boolean {
        // Both halves must hold before this can claim to have recovered: something
        // has to know the plugin, and it has to actually stop rendering. `&&` short
        // circuits, so an unknown plugin is never quarantined.
        // Marked BEFORE the quarantine step, not after the whole thing succeeds.
        // The dialog slot is already released by the time this runs, and
        // quarantineSafely goes through the steps - which in production scan every
        // live manager first. A second crash from the same plugin inside that
        // window would claim the freed slot and open a second dialog for a plugin
        // already being recovered. Marked here rather than inside the steps so
        // every implementation gets it, and so the crash handler can tell a
        // recovery quarantine from an ordinary contained render fault.
        //
        // Set even if the quarantine below fails: that path terminates, so a marker
        // left behind outlives nothing.
        val actionable = isActionable(pluginId)
        if (actionable) PluginRecoveryQuarantine.mark(pluginId)
        val contained = actionable && quarantineSafely(pluginId, error)
        if (contained) {
            logger.warn(
                LogCategory.SYSTEM,
                "Recovering from plugin crash - disabling the plugin, keeping the app",
                mapOf(
                    "pluginId" to pluginId,
                    "errorType" to error.javaClass.simpleName,
                ),
            )
            notifySafely(pluginId)
            scope.launch { unload(pluginId) }
        }
        return contained
    }

    /** The one honest reason to refuse: there is no such plugin to take out. */
    private fun isActionable(pluginId: String): Boolean {
        val known = steps.isKnown(pluginId)
        if (!known) {
            logger.warn(
                LogCategory.SYSTEM,
                "Crash attributed to a plugin nothing knows about - cannot recover",
                mapOf("pluginId" to pluginId),
            )
        }
        return known
    }

    /**
     * Record the plugin as crashed, and report honestly if even that did not happen.
     *
     * What this actually guarantees, stated precisely because the obvious stronger
     * claim is false: the plugin is *recorded* as crashed, so repeat crashes are
     * suppressed and any mounted [ai.rever.boss.plugin.sandbox.ui.PluginErrorBoundary]
     * will swap to its fallback on the next EDT cycle. It does **not** guarantee the
     * plugin has stopped - in the case this feature targets there is no boundary
     * mounted at all, so nothing observes the entry and only the background unload
     * really removes it.
     *
     * The step is still gated rather than assumed: the current implementation is a
     * map write that does not throw, but a steps implementation that fails here has
     * done nothing, and reporting success would dismiss the dialog over a plugin
     * that is entirely untouched.
     */
    @Suppress("TooGenericExceptionCaught") // The plugin layer throws Errors, not just Exceptions.
    private fun quarantineSafely(
        pluginId: String,
        error: Throwable,
    ): Boolean =
        try {
            steps.quarantine(pluginId, error)
            true
        } catch (t: Throwable) {
            logger.error(
                LogCategory.SYSTEM,
                "Failed to quarantine crashed plugin",
                mapOf("pluginId" to pluginId),
                t,
            )
            false
        }

    /** A failed toast must not turn a successful recovery into a termination. */
    @Suppress("TooGenericExceptionCaught")
    private fun notifySafely(pluginId: String) {
        try {
            steps.notifyDisabling(pluginId)
        } catch (t: Throwable) {
            logger.warn(
                LogCategory.SYSTEM,
                "Could not show the plugin-disabled notification",
                mapOf("pluginId" to pluginId),
                t,
            )
        }
    }

    /**
     * The background half. Each step is independent: a plugin whose tabs refuse
     * to close must still be disabled, and a disable that fails must still be
     * persisted, or the next launch loads the same crashing plugin.
     */
    private suspend fun unload(pluginId: String) {
        runCatching { steps.closeTabs(pluginId) }
            .onFailure { logFailure("close the crashed plugin's tabs", pluginId, it) }
        val disabled =
            runCatching { steps.disable(pluginId) }
                .onFailure { logFailure("disable the crashed plugin", pluginId, it) }
                .getOrDefault(false)
        val persisted =
            runCatching { steps.persistDisabled(pluginId) }
                .onFailure { logFailure("persist the disabled state", pluginId, it) }
                .getOrDefault(false)
        logger.info(
            LogCategory.SYSTEM,
            "Crashed plugin unloaded",
            mapOf(
                "pluginId" to pluginId,
                "disabledInManagers" to disabled.toString(),
                "persisted" to persisted.toString(),
            ),
        )
        // The user was told the plugin is being disabled before any of this ran.
        // If it did not take - no live manager accepted the disable, or the state
        // could not be written - they are owed the correction, or they will meet
        // the same plugin, enabled, at the next launch.
        if (!disabled || !persisted) {
            runCatching { steps.notifyDisableIncomplete(pluginId) }
                .onFailure { logFailure("report an incomplete disable", pluginId, it) }
        }
        if (!disabled) {
            // The plugin is still enabled and still running, so muting it would be
            // the very failure this feature was accused of and fixed once already:
            // a live, still-throwing plugin whose crash reporting is silently off
            // for the rest of the session, on the strength of one toast the user
            // may not have been looking at. Suppression is for a plugin that was
            // actually taken out; this one was not.
            PluginRecoveryQuarantine.clear(pluginId)
            logger.warn(
                LogCategory.SYSTEM,
                "Could not disable the crashed plugin - leaving its crash reporting on",
                mapOf("pluginId" to pluginId),
            )
        }
    }

    private fun logFailure(
        what: String,
        pluginId: String,
        cause: Throwable,
    ) = logger.warn(
        LogCategory.SYSTEM,
        "Failed to $what",
        mapOf("pluginId" to pluginId),
        cause,
    )
}
