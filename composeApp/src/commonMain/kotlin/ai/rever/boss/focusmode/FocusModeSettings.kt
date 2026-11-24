package ai.rever.boss.focusmode

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The four window edges focus mode can clear, each independently switchable.
 */
enum class FocusModeEdge {
    TOP,
    LEFT,
    RIGHT,
    BOTTOM,
}

/**
 * Configuration for Focus Mode feature.
 * Focus Mode minimizes distractions by hiding UI chrome (top bar, sidebars, bottom bar)
 * while keeping tabs and main content visible.
 *
 * @property enabled Whether focus mode is currently active
 * @property autoRevealEnabled Whether to auto-reveal hidden bars on mouse hover at edges
 * @property revealOffsetPx Distance in pixels from window edge to trigger auto-reveal
 * @property revealDelayMs Delay in milliseconds before reveal triggers after hovering at edge
 * @property hideTopBar Whether focus mode clears the top action bar
 * @property hideLeftSidebar Whether focus mode clears the left sidebar
 * @property hideRightSidebar Whether focus mode clears the right sidebar
 * @property hideBottomBar Whether focus mode clears the bottom status bar
 */
@Serializable
data class FocusModeSettings(
    val enabled: Boolean = false,
    val autoRevealEnabled: Boolean = true,
    val revealOffsetPx: Float = 30f,
    val revealDelayMs: Long = 500L,
    val hideTopBar: Boolean = true,
    val hideLeftSidebar: Boolean = true,
    val hideRightSidebar: Boolean = true,
    val hideBottomBar: Boolean = true,
) {
    /** Whether [edge] is cleared right now: focus mode is on and that edge opted in. */
    fun hides(edge: FocusModeEdge): Boolean =
        enabled &&
            when (edge) {
                FocusModeEdge.TOP -> hideTopBar
                FocusModeEdge.LEFT -> hideLeftSidebar
                FocusModeEdge.RIGHT -> hideRightSidebar
                FocusModeEdge.BOTTOM -> hideBottomBar
            }

    /** Whether any edge is cleared - focus mode with all four off changes nothing. */
    fun hidesAnything(): Boolean = FocusModeEdge.entries.any { hides(it) }

    companion object {
        /**
         * Whether edge hover-to-reveal should be on out of the box, for [osName].
         *
         * **Off on Windows.** Reveal is driven by Compose `onPointerEvent(Enter/Exit)` on edge
         * strips, and Windows runs the browser in HARDWARE mode, where Chromium owns a foreign
         * native window that composites over the Compose scene rather than inside it. The OS
         * delivers pointer events to that window, so Compose never sees the pointer cross an edge
         * strip that sits under the browser. A Windows user in focus mode with a browser tab open
         * would sweep the edge and get nothing back - the bars simply would not return, with no
         * indication why.
         *
         * The setting itself still works, and anyone who wants it can turn it on. This only
         * changes what a fresh install starts with, and only where the mechanism cannot deliver.
         *
         * `startsWith`, not `contains`: `"darwin"` contains `"win"`, and handing macOS the
         * Windows branch here would silently disable a feature that works perfectly well there.
         * The same trap is pinned in `ResourceModeTest` and `JxBrowserRenderingModeTest`.
         */
        fun defaultAutoReveal(osName: String): Boolean = !isWindows(osName)

        /**
         * Whether focus mode clears the two sidebars out of the box, for [osName].
         *
         * **Off on Windows**, for the same reason [defaultAutoReveal] is: with hover-reveal
         * unable to fire there, hiding a sidebar is a one-way door. The top and bottom bars are
         * still cleared, so focus mode does something on Windows; the sidebars stay, because they
         * are what a user reaches for mid-task and what they could not get back.
         *
         * This is only the starting point. All four edges are individually switchable in
         * Settings, so a Windows user who wants the full sweep can have it.
         */
        fun defaultHidesSidebars(osName: String): Boolean = !isWindows(osName)

        /** Fresh settings for [osName], used on first run and by "Reset to defaults". */
        fun defaultsFor(osName: String) =
            FocusModeSettings(
                autoRevealEnabled = defaultAutoReveal(osName),
                hideLeftSidebar = defaultHidesSidebars(osName),
                hideRightSidebar = defaultHidesSidebars(osName),
            )

        /**
         * Keys introduced with the per-edge switches, and the *only* ones an absence may be
         * resolved from the platform defaults.
         *
         * The distinction is not cosmetic. Files written before this build used
         * `encodeDefaults = false`, so they omit every value that equals the CLASS default -
         * an absence there means "the user's choice happened to match the class default", not
         * "never chosen". Resolving those from the platform defaults would silently revert a
         * real preference: a Windows user who deliberately switched hover-to-reveal ON wrote a
         * file with `autoRevealEnabled` absent (it equals the class default `true`), and the
         * Windows platform default is `false`. These four keys are safe precisely because no
         * file written before this build can contain them.
         */
        private val PLATFORM_DEFAULTED_KEYS =
            setOf("hideTopBar", "hideLeftSidebar", "hideRightSidebar", "hideBottomBar")

        /**
         * Decode a stored settings file, resolving the keys it predates from [defaults].
         *
         * A plain decode fills a missing key from the *class* default, which is the same on
         * every platform - so a Windows install that already had a settings file would come up
         * hiding both sidebars with no way to reveal them, which is exactly what
         * [defaultHidesSidebars] exists to prevent.
         *
         * Only the keys in [PLATFORM_DEFAULTED_KEYS] are resolved that way. Every other absent
         * key keeps the old meaning and falls back to the class default, because an older
         * writer omitted class-default values and an absence there is a choice, not a gap. Keys
         * present in the file always win either way.
         *
         * A future platform-specific default needs its key added to that set, in the build that
         * introduces it - which is the same discipline as bumping a schema version, expressed as
         * the thing it actually protects.
         */
        fun decodeWithDefaults(
            content: String,
            defaults: FocusModeSettings,
        ): FocusModeSettings {
            val stored = storageJson.parseToJsonElement(content).jsonObject
            val defaulted =
                storageJson
                    .encodeToJsonElement(serializer(), defaults)
                    .jsonObject
                    .filterKeys { it in PLATFORM_DEFAULTED_KEYS }
            return storageJson.decodeFromJsonElement(serializer(), JsonObject(defaulted + stored))
        }

        private fun isWindows(osName: String) = osName.lowercase().startsWith("win")

        /**
         * The one encoder for the settings file - used by `FocusModeSettingsManager` to write
         * it and by [decodeWithDefaults] to merge it.
         *
         * `encodeDefaults` is load-bearing, and the two halves have to agree about it, which
         * is why they share an instance rather than each configuring their own. Without it a
         * value equal to the CLASS default is omitted on write, and the merge reads that
         * absence as "never chosen" and substitutes the PLATFORM default. On Windows those
         * disagree for `autoRevealEnabled`, `hideLeftSidebar` and `hideRightSidebar`, so a
         * user switching a sidebar back on would write nothing and find it off again next
         * launch - on the one platform where that switch is the whole escape hatch.
         */
        internal val storageJson =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}
