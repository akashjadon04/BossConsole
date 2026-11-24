package ai.rever.boss.utils

/**
 * Who currently owns a URL scheme or a file type, from BOSS's point of view.
 *
 * A boolean was the wrong shape and it produced a wrong message. On a machine
 * where the branded Chromium engine had claimed http/https - which is the
 * default outcome of every install that predates the branding fix, because both
 * bundles are called "BOSS" and System Settings cannot distinguish them - the
 * old `isDefaultBrowser(): Result<Boolean>` answered false, and the Settings
 * card said "BOSS is not your default browser" to someone who had set it and
 * whose links were being handed to a bare rendering engine. [OurEngine] exists
 * so that case can be named and offered a repair instead.
 */
internal sealed interface DefaultHandlerState {
    /** BOSS itself is registered. Nothing to do. */
    data object Ours : DefaultHandlerState

    /**
     * A BOSS *component* is registered rather than BOSS: today only the
     * Chromium engine bundle, [BOSS_MACOS_ENGINE_BUNDLE_ID].
     *
     * Distinct from [Other] because the fix is different. For [Other] the user
     * chose another app and BOSS should ask; here nobody chose anything - two
     * indistinguishable entries were offered and the wrong one was picked - so
     * BOSS can simply take it back.
     */
    data object OurEngine : DefaultHandlerState

    /** Some other application, or nothing at all when [bundleId] is null. */
    data class Other(
        val bundleId: String?,
    ) : DefaultHandlerState

    val isOurs: Boolean get() = this is Ours

    companion object {
        /**
         * Collapses per-type states into one, preferring the answer the user can
         * act on.
         *
         * [OurEngine] outranks [Other] because it is the answer with a one-click
         * fix, and it stays true and repairable whether one type in a category is
         * affected or all of them. An empty collection is [Other] with no name:
         * nothing to reduce is not "everything is fine".
         *
         * Lives here rather than in one of the three platform handlers, each of
         * which had its own copy of this fold. They agreed, which is the only
         * reason nothing was broken; a fourth copy would not have.
         */
        fun reduce(states: Collection<DefaultHandlerState>): DefaultHandlerState =
            when {
                states.isEmpty() -> Other(null)
                states.all { it.isOurs } -> Ours
                states.any { it is OurEngine } -> OurEngine
                else -> states.first { !it.isOurs }
            }

        /**
         * Classifies a bundle id as reported by Launch Services.
         *
         * Case-insensitive, because bundle identifiers are: the OS will happily
         * return a differently-cased spelling of the id in an app's own
         * Info.plist, and a case-sensitive comparison here would report BOSS as
         * "some other app".
         */
        fun of(bundleId: String?): DefaultHandlerState =
            when {
                bundleId == null -> Other(null)
                bundleId.equals(BOSS_MACOS_BUNDLE_ID, ignoreCase = true) -> Ours
                bundleId.equals(BOSS_MACOS_ENGINE_BUNDLE_ID, ignoreCase = true) -> OurEngine
                else -> Other(bundleId)
            }
    }
}
