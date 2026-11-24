package ai.rever.boss.components.dividers

import ai.rever.boss.layout.BossChrome
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material.Divider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's vertical hairline.
 *
 * Width comes from `ChromeDimens.dividerThickness` rather than a literal, because `ChromeMetrics`
 * charges the budget for one of these down each icon strip's inner edge. A literal here made that
 * token measurement-only: changing it moved the reported number and not a single pixel, which is
 * the exact drift `ChromeMetrics` exists to prevent, inside `ChromeMetrics`.
 */
@Composable
fun VDivider(modifier: Modifier = Modifier) {
    Divider(
        modifier =
            modifier
                .fillMaxHeight()
                .width(BossChrome.dimens.dividerThickness),
        color = BossTheme.colors.line,
    )
}
