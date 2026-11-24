package ai.rever.boss.updater

/**
 * Whether [UpdateBanner] draws anything for this state.
 *
 * Exhaustive on purpose - no `else` - so a new [UpdateState] has to say whether it puts a banner on
 * screen. The layout asks this to decide whether the macOS traffic lights land on the banner or on
 * the chrome below it, and an answer of "yes" for a state that draws nothing reserves a strip of
 * clearance in front of lights that are sitting somewhere else entirely.
 *
 * It mirrors the `when` in [UpdateBanner], which does have an `else`; UpdateBannerVisibilityTest is
 * what keeps the two from drifting.
 */
fun UpdateState.drawsBanner(): Boolean =
    when (this) {
        is UpdateState.UpdateAvailable,
        is UpdateState.Downloading,
        is UpdateState.ReadyToInstall,
        is UpdateState.RestartRequired,
        is UpdateState.Error,
        -> true

        is UpdateState.Idle,
        is UpdateState.CheckingForUpdates,
        is UpdateState.UpToDate,
        is UpdateState.Installing,
        -> false
    }
