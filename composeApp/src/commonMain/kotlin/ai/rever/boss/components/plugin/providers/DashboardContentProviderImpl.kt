package ai.rever.boss.components.plugin.providers

import ai.rever.boss.components.home.HomeScreen
import ai.rever.boss.plugin.api.DashboardContentProvider
import androidx.compose.runtime.Composable

/**
 * Serves the host's home screen to a browser plugin showing about:blank.
 *
 * **This used to be where the home screen stopped working.** It rendered the old `Dashboard`
 * with eleven of its twelve callbacks as `{ /* No-op for browser plugin */ }` and
 * `onShowSettings = null`, so on this surface every project card, file card, split template,
 * "Open File", "New Project", "New Tab", "New Terminal", "New Window", "Open Project" and
 * "Settings" rendered normally and did nothing when clicked. Only `onOpenUrl` was wired.
 *
 * [HomeScreen] takes no action callbacks - it emits on `DashboardEventBus` and reads its
 * registries from composition locals - so there is nothing left here to get wrong, and
 * `onNavigate` is no longer needed either: opening a URL goes on the same bus as everything
 * else, which opens it in a new tab rather than replacing this one.
 */
class DashboardContentProviderImpl : DashboardContentProvider {
    @Composable
    override fun DashboardContent(onNavigate: (String) -> Unit) {
        HomeScreen()
    }
}
