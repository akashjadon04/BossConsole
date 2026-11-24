package ai.rever.boss.components.bars.horizontal

import ai.rever.boss.components.dialogs.DownloadCenterDialog
import ai.rever.boss.downloads.DownloadCenter
import ai.rever.boss.downloads.overallProgress
import ai.rever.boss.downloads.transferBarLabel
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Live transfer progress in the bottom bar, and the way into [DownloadCenterDialog].
 *
 * Renders nothing while nothing is in flight, so the bar stays clean. This used
 * to be a widget the Toolbox plugin contributed, which is why every download the
 * host itself started - an update from a panel badge, a missing dependency, the
 * application's own update - happened with no visible progress at all.
 *
 * Per-window on purpose: the dialog opens in the window whose bar was clicked,
 * and the transfers behind it are process-wide, so two windows can both have it
 * open and see the same rows.
 */
@Composable
fun DownloadCenterStatusItem() {
    val transfers by DownloadCenter.transfers.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    // Reset here, not only inside the dialog: the early return below removes the
    // dialog's subtree in the same recomposition that empties the list, so its own
    // LaunchedEffect is disposed before it can dispatch and `showDialog` would stay
    // true - the next download then pops the dialog open with nobody clicking.
    LaunchedEffect(transfers.isEmpty()) {
        if (transfers.isEmpty()) showDialog = false
    }
    if (transfers.isEmpty()) return

    val infos = transfers.map { it.info }
    val overall = overallProgress(infos)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .clickable { showDialog = true }
                .padding(horizontal = 8.dp),
    ) {
        Text(
            text = transferBarLabel(infos),
            fontSize = 11.sp,
            color = BossTheme.colors.textSecondary,
            maxLines = 1,
        )
        Spacer(Modifier.width(6.dp))
        val barModifier =
            Modifier
                .width(72.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
        if (overall != null) {
            LinearProgressIndicator(
                progress = overall,
                modifier = barModifier,
                color = BossTheme.colors.signal,
                backgroundColor = BossTheme.colors.signal.copy(alpha = 0.2f),
            )
        } else {
            LinearProgressIndicator(
                modifier = barModifier,
                color = BossTheme.colors.signal,
                backgroundColor = BossTheme.colors.signal.copy(alpha = 0.2f),
            )
        }
    }

    if (showDialog) {
        DownloadCenterDialog(onDismiss = { showDialog = false })
    }
}
