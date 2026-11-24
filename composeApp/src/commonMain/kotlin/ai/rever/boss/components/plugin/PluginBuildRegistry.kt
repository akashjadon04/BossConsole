package ai.rever.boss.components.plugin

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Which build of a plugin is actually running.
 *
 * The manifest version alone cannot answer this. A locally built jar and the released one carry
 * the same `version`, and an in-place hot reload (the evolver copying a jar over the same path)
 * rewrites neither the manifest nor `installed.json` - so without this, a panel running code that
 * was never published looks identical to the store build, and successive reload iterations look
 * identical to each other.
 *
 * Three independent facts, because one jar can be several of them:
 *
 * - [signedBytes] - a store signature covers these exact bytes. A locally rebuilt jar cannot carry a
 *   valid one, so this is the only signal that can rule a hot reload OUT.
 * - [storeSourced] - the install record says this plugin came from the store or is a system plugin.
 *   Weaker than [signedBytes] and deliberately so: it describes where the plugin came from, not what
 *   is in the file now, so it survives someone overwriting the jar.
 * - [reloadStamp] - the bytes on disk were replaced after the install was recorded, i.e. this is a
 *   hot reload. Carries the jar's modification time, which is what makes one reload iteration
 *   distinguishable from the next.
 *
 * **Why two store signals rather than one.** Signature enforcement is still rolling out
 * (`PluginSignatureEnforcement.DEFAULT` is false), and a store row that has not been signed yet
 * downloads with a null signature - which `PluginSignatureSidecar.persist` writes as *no sidecar at
 * all*. Reading "no sidecar" as "local build" would therefore label a genuine store install DEBUG.
 * So absence of a signature no longer implies a local build on its own; it takes absence of any
 * store origin too. The sidecar signal is only ever as good as the backfill behind it.
 */
data class PluginBuildInfo(
    val pluginId: String,
    val displayName: String,
    /** The canonical manifest version, never suffixed - update checks and signing anchors use it. */
    val version: String,
    val signedBytes: Boolean,
    val storeSourced: Boolean,
    val reloadStamp: Long?,
) {
    /** Nothing says these bytes were ever published. */
    val isLocalBuild: Boolean get() = !signedBytes && !storeSourced

    /**
     * The version as a person should read it: `1.0.3`, `1.0.3-debug`, `1.0.3-debug+1754890231447`.
     *
     * `-debug` says the store never vetted these bytes; `+<millis>` identifies which hot reload you
     * are looking at. Display only. Nothing parses this back, and it is never written to the
     * `version` field of any record.
     */
    val displayVersion: String
        get() =
            buildString {
                append(version)
                if (isLocalBuild) append("-debug")
                reloadStamp?.let {
                    append('+')
                    append(it)
                }
            }

    /** Short pill text, or null for a released build (which gets no tag at all). */
    val tagLabel: String?
        get() =
            when {
                reloadStamp != null -> "HOT"
                isLocalBuild -> "DEBUG"
                else -> null
            }

    /** Whether this build is worth tagging in the UI. */
    val isTagged: Boolean get() = tagLabel != null

    /** Long form for tooltips, accessibility and the overflow menu's version row. */
    val description: String
        get() =
            when {
                reloadStamp != null -> "Hot reloaded build, not the store version"
                isLocalBuild -> "Local build, not the store version"
                else -> "Store version"
            }
}

/**
 * Host-side, commonMain-visible registry of which build each plugin is running. Populated from the
 * desktop layer through `DynamicPluginManager.pluginBuildProbe` on every successful install, which
 * covers cold start, update, reload and evolver hot reload alike.
 *
 * Mirrors [PluginUpdateRegistry] deliberately: panel headers already observe that one for the
 * "update available" badge, so the tag rides the same shape rather than inventing a second.
 */
object PluginBuildRegistry {
    private val _builds = MutableStateFlow<Map<String, PluginBuildInfo>>(emptyMap())
    val builds: StateFlow<Map<String, PluginBuildInfo>> = _builds.asStateFlow()

    /**
     * Atomic read-modify-write, matching [PluginUpdateRegistry]: installs run under the manager's
     * mutex but this is published after it releases, so two plugins finishing at once must not
     * clobber each other.
     */
    fun put(info: PluginBuildInfo) {
        _builds.update { it + (info.pluginId to info) }
    }

    fun clear(pluginId: String) {
        _builds.update { it - pluginId }
    }

    fun get(pluginId: String): PluginBuildInfo? = _builds.value[pluginId]

    /** For tests: drop everything, so one test's registrations cannot leak into the next. */
    fun reset() {
        _builds.value = emptyMap()
    }
}
