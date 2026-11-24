package ai.rever.boss.components.bars.vertical

import ai.rever.boss.app.SIDEBAR_ICON_SIZE
import ai.rever.boss.components.bars.ChromeBar
import ai.rever.boss.components.bars.rememberBarContextMenuItems
import ai.rever.boss.components.buttons.ToolLauncherButton
import ai.rever.boss.components.dividers.SDivider
import ai.rever.boss.components.dividers.VDivider
import ai.rever.boss.components.misc.DraggableSidebarSection
import ai.rever.boss.components.model.BossDraggableComponent
import ai.rever.boss.components.overlays.contextMenu
import ai.rever.boss.components.sidebar.SidebarIconRail
import ai.rever.boss.components.sidebar.SidebarVisibilitySettings
import ai.rever.boss.components.sidebar.SidebarVisibilitySettingsManager
import ai.rever.boss.components.sidebar.computeSlotIconLimits
import ai.rever.boss.layout.BossChrome
import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.Panel.Companion.right
import ai.rever.boss.plugin.api.Panel.Companion.top
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BossDraggableComponent.BossLeftSideBar(
    /**
     * Opens the tools dialog, when this bar is the one carrying the launcher - i.e. when the
     * RIGHT strip is switched off. Null means it is not, and no button is drawn.
     *
     * A callback rather than a flag, because the dialog is owned by the window: see
     * `ToolLauncherButton` for the overlay that made that necessary.
     */
    onOpenTools: (() -> Unit)? = null,
    /** Whether the tools dialog is open, so its launcher reads as selected while it is. */
    toolsOpen: Boolean = false,
) {
    // Customize button can be dragged between the three left-side
    // sections; render it at the bottom of whichever slot the user
    // last dropped it into.
    val visibility by SidebarVisibilitySettingsManager.currentSettings.collectAsState()
    val customizeSlotId = visibility.customizeButtonSlotId
    val customizeOnThisBar = SidebarVisibilitySettings.isLeftSide(customizeSlotId)

    VerticalBar(
        width = BossChrome.dimens.stripWidth,
        // On the bar, not on the weighted Spacer below: the icons carry their own contextMenu and
        // consume the press first, so this fires on empty rail and nowhere else - the same
        // arrangement the top bar has had all along.
        modifier = Modifier.contextMenu(items = rememberBarContextMenuItems(ChromeBar.LEFT_STRIP)),
    ) {
        // BoxWithConstraints gives the rail's full height so adaptive
        // mode can budget icon rows; recomposes on window resize.
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val iconLimits =
                computeSlotIconLimits(
                    slots = listOf(left.top.top, left.top.bottom, left.bottom),
                    settings = visibility,
                    barHeight = maxHeight,
                    reservedHeight =
                        SidebarIconRail.SectionDivider +
                            (if (customizeOnThisBar) SidebarIconRail.CustomizeButton else 0.dp) +
                            (if (onOpenTools != null) SidebarIconRail.ToolLauncherButton else 0.dp),
                )
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                DraggableSidebarSection(
                    slot = left.top.top,
                    maxVisibleIcons = iconLimits[left.top.top],
                )
                if (customizeSlotId == SidebarVisibilitySettings.SLOT_LEFT_TOP_TOP) {
                    SidebarCustomizeMenu(slot = left.top.top)
                }
                SDivider()
                DraggableSidebarSection(
                    slot = left.top.bottom,
                    maxVisibleIcons = iconLimits[left.top.bottom],
                )
                if (customizeSlotId == SidebarVisibilitySettings.SLOT_LEFT_TOP_BOTTOM) {
                    SidebarCustomizeMenu(slot = left.top.bottom)
                }
                Spacer(modifier = Modifier.weight(1f))
                DraggableSidebarSection(
                    slot = left.bottom,
                    maxVisibleIcons = iconLimits[left.bottom],
                )
                if (customizeSlotId == SidebarVisibilitySettings.SLOT_LEFT_BOTTOM) {
                    SidebarCustomizeMenu(slot = left.bottom)
                }
                // Below the slots, at the foot of the rail: this is host chrome rather than a
                // plugin icon, and the bottom is where the bar already keeps the controls that
                // are not draggable. Its height is reserved above, or a full rail would spend the
                // whole budget on plugin icons and push it off screen.
                onOpenTools?.let { open -> RailToolLauncher(open, right, toolsOpen) }
            }
        }
    }
    VDivider()
}

/**
 * The tools launcher as a rail icon: one [SidebarIconRail.RowPitch], like every icon beside it.
 *
 * BOTH halves are here rather than inside [ToolLauncherButton], because that button also sits in
 * the floating cluster and the bar's foot, which set their own size and spacing - a size baked
 * into the button made it the one that did not match wherever it went.
 *
 * The size is not decoration. Without it the button fell back to `BossActionButton`'s intrinsic
 * ~28dp, so it drew visibly smaller than the plugin icons directly above it, while
 * [SidebarIconRail.ToolLauncherButton] reserved a full 40dp row for it and its own KDoc described
 * a 32dp icon. Padding OUTSIDE the size, so the two come to exactly that reserved row.
 */
@Composable
internal fun RailToolLauncher(
    onClick: () -> Unit,
    hintDirection: Panel,
    isSelected: Boolean = false,
) {
    ToolLauncherButton(
        onClick = onClick,
        hintDirection = hintDirection,
        isSelected = isSelected,
        modifier = Modifier.padding(vertical = 4.dp).size(SIDEBAR_ICON_SIZE),
    )
}
