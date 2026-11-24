package ai.rever.boss.components.buttons

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

/** Test tag of the launcher - see `VerticalBarHostActionsLayoutTest`, which asserts its size. */
internal const val TOOL_LAUNCHER_TAG = "tool-launcher-button"

/**
 * The way into every tool, for windows where the icon strips are not both on screen.
 *
 * The icon is the app-grid, the shape a launcher or a store wears everywhere else - a thing you
 * open to go and pick something. Deliberately not an arrow pointing at the missing strip, which
 * would name a scope this does not have (the dialog lists every tool whichever strip is gone),
 * and deliberately not a puzzle piece: that says "plugin", an implementation detail, where what
 * the user is looking for is a tool. `GlobalSearchDialog` already uses this glyph for the
 * everything-category, so a grid of squares means the same thing in both places.
 *
 * **Raises a callback; it does not own the dialog.** An earlier version kept `showDialog` here,
 * which worked in a strip and failed in the floating cluster: that cluster is a heavyweight
 * always-on-top window, `FocusModeQuickActions` swaps it for an in-place rendering the moment the
 * main window loses focus, and opening a dialog is exactly what takes focus away - so the subtree
 * holding the state was disposed and the dialog vanished about 200ms after opening. The same file
 * already says this in prose about the sign-out dialog: the buttons raise callbacks and
 * `BossAppScaffold` owns the state, because a dialog composed inside a content-sized overlay
 * window has nowhere to go.
 *
 * @param hintDirection which way the tooltip opens - away from the edge the button sits on.
 * @param onClick asks the window to open the tools dialog.
 */
@Composable
fun ToolLauncherButton(
    onClick: () -> Unit,
    hintDirection: Panel = bottom,
    /** True while the launcher's dialog is open, so the button reads as the thing that opened it. */
    isSelected: Boolean = false,
    modifier: Modifier = Modifier,
) {
    // Neither padding NOR a size of its own. It shares a row with Sign Out, Settings and Search,
    // and those take both from whoever arranges them: 4dp of padding here made this button 8dp
    // taller than its siblings and dropped its glyph below theirs, and a hardcoded 32dp made it
    // the one button in the floating cluster that did not match the three beside it. The strips
    // and the bar's foot pass their own, matching the rail's other icons.
    BossActionButton(
        imageVector = Icons.Outlined.Apps,
        text = "Tools",
        isSelected = isSelected,
        hintDirection = hintDirection,
        modifier = modifier.testTag(TOOL_LAUNCHER_TAG),
        onClick = onClick,
    )
}
