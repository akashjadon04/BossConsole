package ai.rever.boss.app

/** Where the tools launcher belongs right now. Mutually exclusive by construction. */
internal enum class ToolLauncherPlacement {
    /** Nowhere. Both strips are on screen, so every tool is already one click away. */
    NONE,

    /** In the left strip, below its slots - the right strip is the one that is gone. */
    LEFT_STRIP,

    /** In the right strip, below its slots - the left strip is the one that is gone. */
    RIGHT_STRIP,

    /**
     * Beside Settings, wherever Settings currently is: the top bar, the floating cluster, or the
     * foot of the vertical tab bar. Both strips are gone, so there is no strip to put it in.
     */
    HOST_ACTIONS,
}

/**
 * Where the tools launcher goes, given which icon strips are switched off.
 *
 * A tool is reached by clicking its icon in a strip, and a strip that is switched off takes every
 * tool in it with it - there is no menu, no palette and no other affordance that lists them. With
 * the top bar hidden by default, a window can end up with no way to open a tool at all. This is
 * the answer to "so where does the way in live instead".
 *
 * **Both strips on means NONE, deliberately.** Every tool is already one click away, and a
 * launcher there would be a second way to do a thing that is not hard, taking a row of rail the
 * icons themselves want.
 *
 * **Decided from the settings, never from the reveal flags.** `focusQuickActionsPlacement`
 * documents the trap at length and it applies unchanged here: `FocusModeEdgeRevealState.shown`
 * starts false on every window's first composition, so keying off it would move the launcher on
 * the first frame of every window open. Focus mode's transient hiding is not an input for a
 * second reason too - a strip the user hover-reveals is still their strip, and moving the launcher
 * into a rail for two seconds and back out again is worse than leaving it where it was.
 *
 * Pure and named so the four-way table is testable. The alternative is a conditional inlined in
 * the scaffold that no test can see, whose failure mode is a tool nobody can open.
 */
internal fun toolLauncherPlacement(
    leftStripHidden: Boolean,
    rightStripHidden: Boolean,
): ToolLauncherPlacement =
    when {
        !leftStripHidden && !rightStripHidden -> ToolLauncherPlacement.NONE

        leftStripHidden && rightStripHidden -> ToolLauncherPlacement.HOST_ACTIONS

        // Exactly one is gone: the launcher goes in the one that is left.
        leftStripHidden -> ToolLauncherPlacement.RIGHT_STRIP

        else -> ToolLauncherPlacement.LEFT_STRIP
    }
