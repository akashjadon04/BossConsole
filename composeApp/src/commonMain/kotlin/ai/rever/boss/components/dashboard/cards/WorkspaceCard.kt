package ai.rever.boss.components.dashboard.cards

import ai.rever.boss.components.workspaces.LayoutWorkspace
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.workspace.SplitConfig
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Home-screen card for one [LayoutWorkspace], with a preview of its split layout.
 *
 * Replaces the card that rendered a `SplitTemplate`. The home screen used to list
 * `SplitTemplatesManager.allTemplates` (since deleted) while the top bar, the app menu and the
 * default-workspace setting all listed `WorkspaceManager.workspaces` - two parallel
 * definitions of the same seven layouts, which had already drifted (only the workspace
 * list has Browser Only, and only its Claude Code entry passes `{claudeContinueFlag}`).
 * One list now feeds all of them, so a workspace saved from the top bar shows up here.
 *
 * The preview walks the real [SplitConfig] rather than looking for panels labelled
 * "left" and "right": the template card could only draw two, so Code Review's bottom
 * terminal was missing from a card whose whole job is to show the arrangement.
 */
@Composable
fun WorkspaceCard(
    workspace: LayoutWorkspace,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val scale by animateFloatAsState(
        targetValue = if (isHovered) HOVER_SCALE else 1f,
        animationSpec = spring(dampingRatio = HOVER_SPRING_DAMPING),
    )
    val cardShape = RoundedCornerShape(12.dp)

    Column(
        modifier =
            modifier
                .width(CARD_WIDTH)
                .height(CARD_HEIGHT)
                .scale(scale)
                .clip(cardShape)
                .background(if (isHovered) BossTheme.colors.signalWash else BossTheme.colors.raised)
                .border(
                    width = 1.dp,
                    color =
                        if (isHovered) {
                            BossTheme.colors.signal.copy(alpha = HOVER_BORDER_ALPHA)
                        } else {
                            Color.Transparent
                        },
                    shape = cardShape,
                ).clickable { onClick() }
                .hoverable(interactionSource)
                .padding(12.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        CenteredLine(height = NAME_HEIGHT) {
            Text(
                text = workspace.name,
                color = BossTheme.colors.textPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LayoutPreviewFrame(workspace.layout)

        CenteredLine(height = DESCRIPTION_HEIGHT) {
            Text(
                text = workspace.description,
                color = BossTheme.colors.textSecondary,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The dark inset the split preview is drawn into. */
@Composable
private fun LayoutPreviewFrame(layout: SplitConfig) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .background(BossTheme.colors.ink),
    ) {
        LayoutPreview(layout, Modifier.fillMaxSize())
    }
}

/** Fixed-height centred slot, so cards line up whatever their text runs to. */
@Composable
private fun CenteredLine(
    height: androidx.compose.ui.unit.Dp,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Draw the split tree: a vertical split is a Row, a horizontal split is a Column, a panel
 * is its first tab's type. Recursive, so a nested arrangement draws as it actually is.
 */
@Composable
private fun LayoutPreview(
    layout: SplitConfig,
    modifier: Modifier = Modifier,
) {
    when (layout) {
        is SplitConfig.SinglePanel -> {
            PanelPreview(
                type =
                    layout.panel.tabs
                        .firstOrNull()
                        ?.type,
                modifier = modifier,
            )
        }

        is SplitConfig.VerticalSplit -> {
            Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                LayoutPreview(layout.left, Modifier.weight(1f).fillMaxHeight())
                LayoutPreview(layout.right, Modifier.weight(1f).fillMaxHeight())
            }
        }

        is SplitConfig.HorizontalSplit -> {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LayoutPreview(layout.top, Modifier.weight(1f).fillMaxWidth())
                LayoutPreview(layout.bottom, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PanelPreview(
    type: String?,
    modifier: Modifier = Modifier,
) {
    val (icon, color, label) =
        when (type) {
            "terminal" -> Triple(Icons.Outlined.Terminal, BossTheme.colors.ok, "Term")

            "browser" -> Triple(Icons.Outlined.Language, BossTheme.colors.data, "Web")

            // Deliberate one-off: the design system has no purple token (editor identity color).
            "editor" -> Triple(Icons.Outlined.Code, Color(0xFFB877DB), "Code")

            null -> Triple(Icons.Outlined.Code, BossTheme.colors.textSecondary, "Empty")

            else -> Triple(Icons.Outlined.Code, BossTheme.colors.textSecondary, type)
        }

    Box(
        modifier = modifier.background(BossTheme.colors.raised),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = type,
                tint = color,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = label,
                color = color.copy(alpha = LABEL_ALPHA),
                fontSize = 8.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private val CARD_WIDTH = 180.dp

/** Fixed, so a strip of cards lines up whatever their descriptions run to. */
private val CARD_HEIGHT = 140.dp
private val NAME_HEIGHT = 18.dp
private val PREVIEW_HEIGHT = 50.dp
private val DESCRIPTION_HEIGHT = 28.dp
private const val HOVER_SCALE = 1.02f
private const val HOVER_SPRING_DAMPING = 0.6f
private const val HOVER_BORDER_ALPHA = 0.5f
private const val LABEL_ALPHA = 0.8f
