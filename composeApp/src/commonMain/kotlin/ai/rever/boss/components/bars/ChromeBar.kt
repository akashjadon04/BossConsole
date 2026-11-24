package ai.rever.boss.components.bars

import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.focusmode.FocusModeEdge
import ai.rever.boss.focusmode.FocusModeSettingsManager
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.MenuActionsHandler
import ai.rever.boss.window.WindowAppearanceSettings
import ai.rever.boss.window.WindowAppearanceSettingsManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The four pieces of window chrome that can be hidden independently.
 *
 * Not `FocusModeEdge`, though the two line up one-for-one. That enum names an *edge of the window*
 * for hover-reveal geometry; this names a *bar the user can switch off*, and the two answer to
 * different settings objects. Keeping them separate is what stops a hover strip being laid for an
 * edge that is permanently hidden and has nothing to reveal.
 */
internal enum class ChromeBar {
    TOP,
    BOTTOM,
    LEFT_STRIP,
    RIGHT_STRIP,
}

/** The menu's own wording for [bar], used in "Hide …" and in the View menu's checkboxes. */
internal fun ChromeBar.displayName(): String =
    when (this) {
        ChromeBar.TOP -> "Top Bar"
        ChromeBar.BOTTOM -> "Bottom Bar"
        ChromeBar.LEFT_STRIP -> "Left Strip"
        ChromeBar.RIGHT_STRIP -> "Right Strip"
    }

/** [settings] with [bar] switched to [visible], so the two writers cannot disagree on the mapping. */
internal fun WindowAppearanceSettings.withBarVisible(
    bar: ChromeBar,
    visible: Boolean,
): WindowAppearanceSettings =
    when (bar) {
        ChromeBar.TOP -> copy(showTopBar = visible)
        ChromeBar.BOTTOM -> copy(showBottomBar = visible)
        ChromeBar.LEFT_STRIP -> copy(showLeftStrip = visible)
        ChromeBar.RIGHT_STRIP -> copy(showRightStrip = visible)
    }

/** Whether [bar] is currently switched on. */
internal fun WindowAppearanceSettings.isBarVisible(bar: ChromeBar): Boolean =
    when (bar) {
        ChromeBar.TOP -> showTopBar
        ChromeBar.BOTTOM -> showBottomBar
        ChromeBar.LEFT_STRIP -> showLeftStrip
        ChromeBar.RIGHT_STRIP -> showRightStrip
    }

/**
 * The bar sitting at [edge], for the places that hold a `FocusModeEdge` and need this settings
 * object - the hover strips, which must not lay a band over an edge whose bar is switched off.
 *
 * The two enums stay distinct (see [ChromeBar]) and this is the one seam between them, so the
 * correspondence is written down once rather than re-derived at each call site.
 */
internal fun WindowAppearanceSettings.isBarVisible(edge: FocusModeEdge): Boolean =
    isBarVisible(
        when (edge) {
            FocusModeEdge.TOP -> ChromeBar.TOP
            FocusModeEdge.BOTTOM -> ChromeBar.BOTTOM
            FocusModeEdge.LEFT -> ChromeBar.LEFT_STRIP
            FocusModeEdge.RIGHT -> ChromeBar.RIGHT_STRIP
        },
    )

/**
 * Right-click menu shared by the top bar, the bottom bar and both icon strips.
 *
 * Replaces the two placeholder entries the top bar carried since it was written - an "Edit" and a
 * "Save" whose `onClick` bodies were empty comments, so the one menu in this app a user was most
 * likely to find by accident promised two actions that did not exist.
 *
 * The three entries are the three things a user right-clicking chrome plausibly wants: make this
 * bar go away, clear all the chrome at once, or go and configure it. Hiding is the reason the View
 * menu grew a matching set of checkboxes in the same change - a bar that can be hidden from a menu
 * nobody can find again would be worse than no menu at all.
 *
 * No item carries a `trailingIcon`, deliberately: `isNativeRepresentable` refuses the native macOS
 * NSMenu path for any menu that has one, and on the drawn path this menu would be painted *behind*
 * the browser's native surface on Windows. Leading icons are fine and do not disqualify it.
 */
@Composable
internal fun rememberBarContextMenuItems(bar: ChromeBar): List<ContextMenuItem> {
    val windowId = LocalWindowId.current
    val scope = rememberCoroutineScope()

    // Only the focus-mode flag is collected, because only it changes an item's TEXT. The appearance
    // settings are read at click time from `currentSettings.value` instead, which is what the two
    // neighbouring writers of this same store do (`BossWindow`'s View checkboxes and the
    // `Customize Sidebar…` repair in `BossAppMenuActionEffects`). Composing a snapshot into the
    // lambda would let a click on an already-composed menu write back a stale object and revert a
    // concurrent change from Settings or another window - and keying the remember on it rebuilt all
    // four menus whenever anything unrelated in that store moved, tab sizing included.
    val focusEnabled by
        remember { FocusModeSettingsManager.currentSettings.map { it.enabled }.distinctUntilChanged() }
            .collectAsState(initial = FocusModeSettingsManager.currentSettings.value.enabled)

    return remember(bar, windowId, focusEnabled, scope) {
        listOf(
            ContextMenuItem(
                text = "Hide ${bar.displayName()}",
                icon = Icons.Outlined.VisibilityOff,
                onClick = {
                    scope.launch {
                        WindowAppearanceSettingsManager.updateSettings(
                            WindowAppearanceSettingsManager.currentSettings.value
                                .withBarVisible(bar, visible = false),
                        )
                    }
                },
            ),
            ContextMenuItem(isDivider = true),
            ContextMenuItem(
                text = if (focusEnabled) "Exit Focus Mode" else "Enter Focus Mode",
                icon = Icons.Outlined.CenterFocusStrong,
                onClick = {
                    scope.launch { FocusModeSettingsManager.toggleFocusMode() }
                },
            ),
            ContextMenuItem(isDivider = true),
            ContextMenuItem(
                text = "Appearance settings…",
                icon = Icons.Outlined.Settings,
                onClick = {
                    // Null windowId means we are not hosted in a tracked window, which should not
                    // happen for window chrome. The settings event needs a window to route to, so
                    // do nothing rather than open Settings in every window at once.
                    windowId?.let { id ->
                        // Section by enum NAME: `SettingsSection` lives in desktopMain and this is
                        // commonMain. Same convention as `rememberSidebarSettingsMenuItems`, and
                        // `SettingsWindow` falls back to its default for a name it cannot resolve.
                        MenuActionsHandler.triggerOpenSettings(id, "WINDOW_APPEARANCE")
                    }
                },
            ),
        )
    }
}
