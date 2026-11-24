package ai.rever.boss.plugin.sandbox.ui

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.plugin.sandbox.PluginExecutionBoundary
import java.util.concurrent.ConcurrentHashMap

/**
 * Intercepts uncaught exceptions on the AWT/Compose thread and attributes them to plugins.
 *
 * When a plugin has a binary incompatibility (e.g., `NoSuchMethodError` from a mismatched
 * dependency version), the error occurs during Compose composition and cannot be caught by
 * normal try-catch or [LocalPluginErrorHandler]. This interceptor hooks into the global
 * [Thread.UncaughtExceptionHandler] chain to catch these errors and route them to the
 * appropriate plugin's error handler.
 *
 * ## How it works
 * 1. The [PluginErrorBoundary] registers an interceptor callback for its plugin ID
 * 2. When an uncaught exception occurs, this interceptor inspects the stack trace
 * 3. If the stack trace contains frames from a plugin's classloader or sandbox thread,
 *    the error is attributed to that plugin
 * 4. The registered callback is invoked to set the error state in the boundary's composable
 *
 * ## Thread Safety
 * Uses [ConcurrentHashMap] for the interceptor registry. The exception handler runs on
 * the crashing thread (usually AWT EDT for composition errors).
 */
object PluginCrashInterceptor {
    private val logger = BossLogger.forComponent("PluginCrashInterceptor")

    /**
     * Registered interceptors: pluginId -> error callbacks.
     * A plugin can have several active boundaries at once (panel error
     * boundary + status-bar / settings-page extension boundaries); ALL of
     * them are notified on a crash so each surface can swap to its fallback.
     * Registration/unregistration is per-callback — one boundary leaving
     * composition no longer clobbers the others (the old single-slot map was
     * last-writer-wins).
     */
    private val interceptors =
        ConcurrentHashMap<String, java.util.concurrent.CopyOnWriteArrayList<(Throwable) -> Unit>>()

    /**
     * Cached classloader-to-pluginId mapping, populated at registration time.
     * Avoids calling Class.forName() during exception handling (which could
     * trigger class loading and mask the original error).
     */
    private val classLoaderToPluginId = ConcurrentHashMap<ClassLoader, String>()

    /**
     * The original uncaught exception handler we chain to.
     */
    @Volatile
    private var originalHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var isInstalled = false

    /**
     * Install the crash interceptor by chaining into the global uncaught exception handler.
     *
     * Safe to call multiple times - only installs once. Should be called early in app startup,
     * after [CrashHandler.install()] so we chain properly.
     */
    fun install() {
        if (isInstalled) return
        synchronized(this) {
            if (isInstalled) return

            originalHandler = Thread.getDefaultUncaughtExceptionHandler()

            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                val handled = tryHandlePluginCrash(thread, throwable)
                if (!handled) {
                    // Not a plugin crash (or no interceptor registered) - chain to original handler
                    originalHandler?.uncaughtException(thread, throwable)
                }
            }

            isInstalled = true
            logger.debug(LogCategory.SYSTEM, "Plugin crash interceptor installed")
        }
    }

    /**
     * Register an error callback for a plugin.
     *
     * @param pluginId The plugin ID to intercept errors for
     * @param onError Callback invoked when an error is attributed to this plugin
     * @return A [Registration] that should be disposed when the composable leaves composition
     */
    fun register(
        pluginId: String,
        onError: (Throwable) -> Unit,
    ): Registration {
        interceptors.getOrPut(pluginId) { java.util.concurrent.CopyOnWriteArrayList() }.add(onError)

        // Cache classloader mapping at registration time so we don't need
        // Class.forName() during exception handling (which risks masking errors).
        try {
            val contextLoader = Thread.currentThread().contextClassLoader
            if (contextLoader != null) {
                // Check if this is a PluginClassLoader by reflection
                try {
                    val field = contextLoader.javaClass.getField("pluginId")
                    val loaderId = field.get(contextLoader) as? String
                    if (loaderId == pluginId) {
                        classLoaderToPluginId[contextLoader] = pluginId
                    }
                } catch (_: NoSuchFieldException) {
                    // Not a PluginClassLoader, skip
                } catch (_: Throwable) {
                    // Ignore reflection errors
                }
            }
        } catch (_: Throwable) {
            // Don't let caching fail registration
        }

        logger.debug(
            LogCategory.SYSTEM,
            "Registered crash interceptor",
            mapOf(
                "pluginId" to pluginId,
                "activeInterceptors" to interceptors.size,
            ),
        )
        return Registration(pluginId, onError)
    }

    /**
     * Attempt to handle an uncaught exception as a plugin crash.
     *
     * @return true if the error was attributed to a plugin and handled
     */
    private fun tryHandlePluginCrash(
        thread: Thread,
        throwable: Throwable,
    ): Boolean {
        val pluginId = attributeToPlugin(throwable, thread) ?: return false
        val callbacks = interceptors[pluginId]?.takeIf { it.isNotEmpty() } ?: return false

        logger.warn(
            LogCategory.SYSTEM,
            "Intercepted plugin crash during composition",
            mapOf(
                "pluginId" to pluginId,
                "errorType" to throwable.javaClass.simpleName,
                "thread" to thread.name,
                "message" to (throwable.message ?: "no message"),
            ),
        )

        return invokeAll(pluginId, callbacks, throwable)
    }

    /**
     * Handle a crash attributed to a specific plugin.
     *
     * Called by the custom [WindowExceptionHandlerFactory] in main.kt when Compose catches
     * a composition error and [attributeToPlugin] identifies the responsible plugin.
     *
     * @return true if the callback was invoked successfully
     */
    fun tryHandle(
        pluginId: String,
        throwable: Throwable,
    ): Boolean {
        val callbacks = interceptors[pluginId]?.takeIf { it.isNotEmpty() } ?: return false

        logger.warn(
            LogCategory.SYSTEM,
            "Handling plugin crash via WindowExceptionHandler",
            mapOf(
                "pluginId" to pluginId,
                "errorType" to throwable.javaClass.simpleName,
                "message" to (throwable.message ?: "no message"),
            ),
        )

        return invokeAll(pluginId, callbacks, throwable)
    }

    /**
     * Notify every registered boundary for [pluginId]. Handled when at least
     * one callback ran without throwing — each surface (panel boundary,
     * extension boundaries) flips to its own fallback independently.
     */
    private fun invokeAll(
        pluginId: String,
        callbacks: List<(Throwable) -> Unit>,
        throwable: Throwable,
    ): Boolean {
        var handled = false
        for (callback in callbacks) {
            try {
                callback(throwable)
                handled = true
            } catch (e: Exception) {
                logger.error(
                    LogCategory.SYSTEM,
                    "Error in plugin crash callback",
                    mapOf(
                        "pluginId" to pluginId,
                    ),
                    e,
                )
            }
        }
        return handled
    }

    /**
     * Register a classloader-to-pluginId mapping.
     *
     * Called by plugin loading infrastructure to pre-cache the mapping,
     * avoiding [Class.forName] during exception handling.
     */
    fun registerClassLoader(
        classLoader: ClassLoader,
        pluginId: String,
    ) {
        classLoaderToPluginId[classLoader] = pluginId
    }

    /**
     * Candidates to test a stack trace against: every plugin we know of, not just
     * the ones with a mounted error boundary.
     *
     * The loaded set comes from [KnownPlugins.ids], which the
     * host installs — this module keeps no dependency on `plugin-loader`, the
     * same reason [PluginExecutionBoundary] takes its resolver by injection.
     */
    private fun blameCandidates(): Set<String> = interceptors.keys + classLoaderToPluginId.values + KnownPlugins.ids()

    /**
     * Which plugin is *responsible* for [throwable], whether or not it has a live
     * error boundary.
     *
     * Distinct from [attributeToPlugin], which answers the narrower "which
     * registered boundary should handle it" and is filtered to plugins that can
     * actually render a fallback. Both questions are real and conflating them is
     * what let a plugin take the host down:
     *
     * `TerminalTabPluginAPIImpl.setPendingSidebarCommand` recursed into itself and
     * threw `StackOverflowError` from a click. Every one of the ~1024 frames the
     * JVM kept was `ai.rever.boss.plugin.dynamic.terminaltab.*`, so the culprit was
     * never in doubt — but terminal-tab had no boundary mounted at that moment, so
     * [attributeToPlugin] returned null, `decideWindowExceptionRoute` fell through
     * to the uncontainable check, and the app exited. A plugin with no UI on
     * screen could kill the session precisely *because* it had no UI on screen.
     *
     * Same strategies, same order, minus the registration filter.
     */
    fun blameFor(
        throwable: Throwable,
        thread: Thread? = null,
    ): String? {
        // Exact: the host tagged this while calling into the plugin.
        val tagged = PluginExecutionBoundary.attributionFor(throwable)
        if (tagged != null) return tagged

        val candidates = blameCandidates()
        return if (candidates.isEmpty()) {
            null
        } else {
            byThreadName(candidates, thread)
                ?: byStackFrames(throwable, candidates)
                ?: resolveByClassLoader(throwable) { true }
        }
    }

    /**
     * Which registered boundary should handle [throwable], or null.
     *
     * The narrow question, and the filter is the point of it: a boundary that is
     * not mounted cannot render a fallback, so naming its plugin here would route
     * the crash to nobody. [blameFor] answers the broader "who is responsible",
     * which is what the window handler falls back on so that a plugin with no UI
     * on screen can still be quarantined instead of ending the app.
     *
     * Strategies, in order: the execution-boundary tag, the sandbox thread name,
     * stack-frame package prefixes, then defining classloaders.
     *
     * @return The plugin ID if attributed to a *mounted* boundary, null otherwise
     */
    fun attributeToPlugin(
        throwable: Throwable,
        thread: Thread? = null,
    ): String? {
        // Strategy 0: what the execution boundary recorded before the throw.
        //
        // Consulted first because it is the only exact source - the host tagged the
        // throwable while calling into the plugin, rather than inferring from frames
        // that may already be gone.
        //
        // Still filtered by `interceptors`: this function answers "which registered
        // boundary should handle it", and a tag naming a plugin with no live
        // boundary is not an answer to that question.
        PluginExecutionBoundary
            .attributionFor(throwable)
            ?.takeIf { interceptors.containsKey(it) }
            ?.let { return it }

        val mounted = interceptors.keys
        return byThreadName(mounted, thread)
            ?: byStackFrames(throwable, mounted)
            ?: resolveByClassLoader(throwable) { interceptors.containsKey(it) }
    }

    /**
     * Strategy 3, shared by [attributeToPlugin] and [blameFor].
     *
     * The slow path (O(stackFrames × classloaders), with `Class.forName` calls),
     * so both callers reach it only after the cheap string comparisons have
     * failed. [accept] is what differs between them: the narrow question filters
     * to plugins with a live boundary, the broad one takes any plugin.
     */
    private fun resolveByClassLoader(
        throwable: Throwable,
        accept: (String) -> Boolean,
    ): String? {
        if (classLoaderToPluginId.isEmpty()) return null
        logger.debug(
            LogCategory.UI,
            "Attribution falling through to strategy 3 (classloader resolution)",
            mapOf(
                "errorType" to throwable.javaClass.simpleName,
                "stackDepth" to throwable.stackTrace.size,
                "loaderCount" to classLoaderToPluginId.size,
            ),
        )
        return runCatching {
            throwable.stackTrace.firstNotNullOfOrNull { element ->
                classLoaderToPluginId.entries
                    .firstOrNull { (loader, pId) -> accept(pId) && definedBy(element.className, loader) }
                    ?.value
            }
        }.getOrNull() // Don't let attribution itself crash.
    }

    /**
     * Handle representing a registered interceptor. Call [unregister] to
     * remove ONLY this registration's callback — other boundaries of the same
     * plugin stay registered.
     */
    class Registration(
        private val pluginId: String,
        private val callback: (Throwable) -> Unit,
    ) {
        fun unregister() {
            val callbacks = interceptors[pluginId]
            callbacks?.remove(callback)
            if (callbacks != null && callbacks.isEmpty()) {
                interceptors.remove(pluginId, callbacks)
                // Clean up cached classloader entries only once no boundary
                // for this plugin remains.
                classLoaderToPluginId.entries.removeIf { it.value == pluginId }
            }
            logger.debug(
                LogCategory.SYSTEM,
                "Unregistered crash interceptor",
                mapOf(
                    "pluginId" to pluginId,
                    "activeInterceptors" to interceptors.size,
                ),
            )
        }
    }
}

// The three pure strategies live at file level rather than on the object. They
// need only their arguments, and the object is at detekt's function ceiling -
// which is a fair signal here, since none of these is part of its surface.

/** Strategy 1: a sandbox thread names the plugin it was created for. */
private fun byThreadName(
    candidates: Set<String>,
    thread: Thread?,
): String? {
    val name = thread?.name ?: Thread.currentThread().name
    return candidates.firstOrNull { name.contains("plugin-sandbox-$it") }
}

/**
 * Strategy 2: a frame's class sits under the plugin's package.
 *
 * The trailing dot is load-bearing - without it `dynamic.terminal` matches every
 * frame of `dynamic.terminaltab` and blames the wrong plugin.
 */
private fun byStackFrames(
    throwable: Throwable,
    candidates: Set<String>,
): String? =
    runCatching {
        throwable.stackTrace.firstNotNullOfOrNull { element ->
            candidates.firstOrNull { element.className.startsWith("$it.") }
        }
    }.getOrNull() // Attribution must never be the thing that fails.

/** Whether [loader] is the one that defined [className], not merely able to see it. */
private fun definedBy(
    className: String,
    loader: ClassLoader,
): Boolean =
    runCatching {
        Class.forName(className, false, loader).classLoader == loader
    }.getOrDefault(false)
