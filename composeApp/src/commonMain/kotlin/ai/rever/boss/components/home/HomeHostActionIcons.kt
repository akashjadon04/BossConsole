package ai.rever.boss.components.home

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icons for the host's own tool tiles.
 *
 * Split from [HomeToolCatalog] only to keep the icon imports out of the file that holds the
 * catalogue rules, which is the part worth reading. `Icons.Outlined` rather than `Icons.Default`
 * to match the tool grid, where plugin-supplied icons dominate and the outlined set reads
 * lighter next to them.
 */
internal object HomeHostActionIcons {
    fun iconFor(action: HomeHostAction): ImageVector =
        when (action) {
            HomeHostAction.NEW_TAB -> Icons.Outlined.Add
            HomeHostAction.NEW_TERMINAL -> Icons.Outlined.Terminal
            HomeHostAction.NEW_WINDOW -> Icons.Outlined.OpenInBrowser
            HomeHostAction.OPEN_FILE -> Icons.Outlined.FolderOpen
            HomeHostAction.OPEN_PROJECT -> Icons.Outlined.Folder
            HomeHostAction.NEW_PROJECT -> Icons.Outlined.CreateNewFolder
            HomeHostAction.SETTINGS -> Icons.Outlined.Settings
            HomeHostAction.SEARCH -> Icons.Outlined.Search
        }
}
