package ai.rever.boss.components.sidebar

import ai.rever.boss.components.overlays.ContextMenuItem
import ai.rever.boss.window.LocalWindowId
import ai.rever.boss.window.MenuActionsHandler
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Right-click menu items shared by every sidebar plugin icon and the
 * overflow "More" button: a single "Sidebar settings" entry that opens
 * the Settings window at the Sidebar section.
 *
 * The section is passed by enum *name* ("SIDEBAR") because
 * `SettingsSection` lives in desktopMain and this helper is commonMain —
 * same convention as the "KEYMAP" literal used by the shortcut help
 * dialog.
 *
 * A stale string does NOT degrade gracefully, so keep it in step with the
 * enum. `resolveSettingsDeepLink` answers `Unresolved` for a name no
 * section claims, and what that means depends on whether the window is
 * already open: a *new* window defaults to FLUCK, but an *open* one is
 * deliberately left exactly where the user had it. So the failure this
 * literal invites is a menu entry that raises the settings window and
 * does nothing else, with only a debug line to say why. Callers in
 * desktopMain (`BossWindow`'s Help menu) name the enum instead and get a
 * compile error; commonMain cannot.
 */
@Composable
fun rememberSidebarSettingsMenuItems(): List<ContextMenuItem> {
    val windowId = LocalWindowId.current
    return remember(windowId) {
        listOf(
            ContextMenuItem(
                text = "Sidebar settings",
                icon = Icons.Default.Settings,
                onClick = {
                    // Null windowId means we're not hosted in a tracked
                    // window (shouldn't happen for the sidebar) — the
                    // settings event needs a window to route to, so do
                    // nothing rather than open settings in every window.
                    windowId?.let { id ->
                        MenuActionsHandler.triggerOpenSettings(id, "SIDEBAR")
                    }
                },
            ),
        )
    }
}
