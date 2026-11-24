package ai.rever.boss.layout

import ai.rever.boss.focusmode.FocusModeEdge
import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.window.WindowAppearanceSettings

/**
 * The appearance as the LAYOUT sees it: a bar focus mode is clearing is not on screen, whatever
 * the preference says.
 *
 * Two rules read these flags to decide where something goes - `macTrafficLightInset` and
 * `toolLauncherPlacement` - and both were reading the preference alone. A strip switched off in
 * Settings and a strip focus mode has cleared are the same thing to a layout: nothing is drawn
 * there. Reading only the preference put the traffic-light clearance on a top bar that focus mode
 * was not drawing, and left the tools launcher out of a window whose strips had both been cleared,
 * which is a window with no way to open a tool at all.
 *
 * **Standing state, not the reveal flag.** `hides` is "focus mode is on and this edge opted in",
 * which does not change when a bar is hover-revealed for a few seconds. Keying off the reveal
 * would move the launcher and the clearance on every hover - the churn `focusQuickActionsPlacement`
 * documents at length and avoids the same way.
 *
 * The consequence of that choice, so it is not mistaken for a bug: while a bar IS hover-revealed,
 * the clearance stays where this said it goes, so on macOS the lights can sit over a revealed
 * strip's first icon for as long as it is up. That is the better of the two, and the same trade
 * the actions' own placement makes.
 */
fun WindowAppearanceSettings.asDrawn(focusMode: FocusModeSettings): WindowAppearanceSettings =
    copy(
        showTopBar = showTopBar && !focusMode.hides(FocusModeEdge.TOP),
        showLeftStrip = showLeftStrip && !focusMode.hides(FocusModeEdge.LEFT),
        showRightStrip = showRightStrip && !focusMode.hides(FocusModeEdge.RIGHT),
        showBottomBar = showBottomBar && !focusMode.hides(FocusModeEdge.BOTTOM),
    )
