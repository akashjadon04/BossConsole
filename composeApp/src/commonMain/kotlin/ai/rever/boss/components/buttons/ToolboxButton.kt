package ai.rever.boss.components.buttons

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.SidebarItem
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * The Toolbox, drawn beside Settings wherever the host's own actions are.
 *
 * **It renders the plugin's OWN sidebar item** - [SidebarItem.icon] and [SidebarItem.label] - not
 * an icon chosen here. The Toolbox already has a mark, the one on its sidebar rail, and picking a
 * second one for this button would mean the same tool showing two faces depending on where you
 * happened to see it. It also means an icon the plugin changes in a release follows it here with
 * no host change at all.
 *
 * Stateless, and deliberately without padding or a size: it shares rows with Sign Out, Settings and
 * Search, and each of those hosts sets both. A size baked in here made the tools launcher the one
 * button that did not match wherever it went.
 */
@Composable
fun ToolboxButton(
    item: SidebarItem,
    onClick: () -> Unit,
    hintDirection: Panel = bottom,
    modifier: Modifier = Modifier,
) {
    BossActionButton(
        imageVector = item.icon,
        text = item.label,
        hintText = item.label,
        hintDirection = hintDirection,
        modifier = modifier.testTag(TOOLBOX_TAG),
        onClick = onClick,
    )
}

/** Test tag of the Toolbox button - see `HostActionsContentTest`. */
internal const val TOOLBOX_TAG = "toolbox-button"
