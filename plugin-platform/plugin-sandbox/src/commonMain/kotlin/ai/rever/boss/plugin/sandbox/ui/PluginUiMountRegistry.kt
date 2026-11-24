package ai.rever.boss.plugin.sandbox.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * How much of each plugin's UI is currently mounted in a composition.
 *
 * This exists for ONE question, asked at exactly one moment: **has this plugin's UI actually been
 * disposed yet?** - and the answer has to be true before its classloader closes.
 *
 * Closing a plugin's tabs is not that answer. Removing a tab mutates the tab model on the EDT and
 * returns; Compose disposes the subtree on a LATER render frame, and it is that frame which runs
 * the plugin's own `onDispose` lambdas. Unloading between the two resolves those lambdas against a
 * closed loader, which is a `NoClassDefFoundError` the loader itself explains:
 *
 * > Plugin classloader for '...' is UNLOADED; refusing to resolve '...' against the host
 * > classloader. Something still referenced the plugin after it was unloaded - that reference is
 * > the bug.
 *
 * The reference in that message is the still-mounted composition. Waiting on this registry is how
 * the unload path stops racing it.
 *
 * Counted rather than flagged, because one plugin can have several surfaces up at once - a tab in
 * each of two windows, a tab and a sidebar panel - and they dispose independently.
 *
 * "Mounted" means **plugin code is composed**, not "a boundary exists". A boundary rendering the
 * error fallback does not count: it composes none of the plugin, so it has no plugin `onDispose`
 * lambdas for an unload to wait on, and counting it only made every unload of a crashed plugin pay
 * the full timeout - worst on crash recovery, which closes tabs before disabling. That is why the
 * registering effect lives in the boundary's content branch rather than above it.
 *
 * **The count must drop LAST**, after the plugin's own `onDispose` lambdas have run, or this
 * reports "disposed" while the very lambdas the caller is waiting for are still to execute. That
 * holds today because the registering effect is the OUTERMOST one in the boundary and Compose
 * dispatches remember observers in reverse slot order - inner before outer. It is written down
 * because nothing enforces it: if that order ever inverted, this would go quiet and the fault would
 * come back with no test failing.
 *
 * **Leak-tolerant, deliberately.** A boundary whose `onDispose` never runs pins its id here for the
 * rest of the session, and every later unload of that plugin then pays the full timeout. That is
 * the safe direction - waiting too long beats closing a loader too early - and the timeout warning
 * names what is still mounted, which is what makes such a leak visible at all.
 */
object PluginUiMountRegistry {
    private val _mounted = MutableStateFlow<Map<String, Int>>(emptyMap())

    /** Plugin id to the number of its boundaries currently mounted. Absent means none. */
    val mounted: StateFlow<Map<String, Int>> = _mounted.asStateFlow()

    /** A plugin boundary entered the composition. */
    fun onMounted(pluginId: String) {
        _mounted.update { it + (pluginId to (it[pluginId] ?: 0) + 1) }
    }

    /** A plugin boundary left the composition, for real - this runs from `onDispose`. */
    fun onDisposed(pluginId: String) {
        _mounted.update { current ->
            val next = (current[pluginId] ?: 0) - 1
            if (next <= 0) current - pluginId else current + (pluginId to next)
        }
    }

    /** Whether any of [pluginId]'s UI is mounted, or any plugin's when null. */
    fun isMounted(pluginId: String? = null): Boolean =
        if (pluginId == null) _mounted.value.isNotEmpty() else _mounted.value.containsKey(pluginId)

    /**
     * Suspend until none of [pluginIds] has UI mounted.
     *
     * **Named plugins, not "everything".** The caller is about to close a specific set of
     * classloaders, and those are the only surfaces whose disposal can race it. Waiting on every
     * plugin instead would mean waiting on UI that is deliberately staying up: sidebar panels
     * survive an API-layer swap by design and re-register on the far side, so an "is anything
     * mounted" predicate could never come true while one was open - it would burn the whole
     * timeout on every swap and log a warning that reads like the fix failed.
     *
     * An empty set returns immediately, which is the common case and must not pay the timeout.
     *
     * Returns true when it got there, false on timeout. **The timeout is not a formality**: nothing
     * guarantees a render frame will arrive. A minimised or fully occluded window may not draw at
     * all, and blocking an unload on a frame that never comes would hang the app instead of risking
     * a fault the crash boundary already contains. A caller that times out should say so and carry
     * on.
     */
    suspend fun awaitDisposed(
        pluginIds: Set<String>,
        timeoutMillis: Long,
    ): Boolean {
        if (pluginIds.isEmpty()) return true
        return withTimeoutOrNull(timeoutMillis) {
            _mounted.first { current -> pluginIds.none(current::containsKey) }
            true
        } ?: false
    }

    /** Which of [pluginIds] still have UI mounted, for a caller reporting a timeout. */
    fun stillMounted(pluginIds: Set<String>): Map<String, Int> = _mounted.value.filterKeys(pluginIds::contains)

    /**
     * Empty the map. **Test-only, and process-wide.**
     *
     * Not concurrency-safe against a live composition: this module runs Compose UI tests in the
     * same JVM, so a test that resets while another has a boundary mounted will drop that mount and
     * the later dispose will decrement from zero. Reset in setUp/tearDown of tests that own what is
     * mounted, and nowhere else.
     */
    fun reset() {
        _mounted.value = emptyMap()
    }
}
