package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.filetypes.ClaimOutcome
import ai.rever.boss.filetypes.DefaultAppStatus
import ai.rever.boss.filetypes.DefaultAppsManager
import ai.rever.boss.filetypes.DefaultAppsSettingsManager
import ai.rever.boss.filetypes.FileTypeCategory
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.DefaultHandlerState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.Divider
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Settings > Default Apps: which file types and links BOSS opens.
 *
 * One row per category rather than per type, because BOSS claims 83 extensions
 * and 56 macOS UTIs and nobody sets 83 switches. See `boss-file-types.json` for
 * the grouping and why it is a resource.
 *
 * The row that matters most is the one saying a **BOSS component** holds the
 * type. On any machine that installed BOSS before the engine bundle stopped
 * declaring `CFBundleURLTypes`, `~/.boss/boss-chromium/BOSS.app` is a second app
 * called "BOSS" in System Settings, and the default browser is very likely that
 * one - so links open a bare Chromium with no BOSS window. "BOSS is not your
 * default browser" was the old message and it was the wrong story to tell
 * somebody who had set it.
 */
@Composable
fun DefaultAppsSettings() {
    var statuses by remember { mutableStateOf<List<DefaultAppStatus>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    // "" while "Set all" runs, a category id while one row runs, null when idle.
    // One field rather than two, so the disabled state cannot disagree with where
    // the spinner is.
    var busyCategoryId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val supported = remember { DefaultAppsManager.isSupported() }

    var declined by remember { mutableStateOf<Set<String>>(emptySet()) }

    suspend fun refresh() {
        loading = true
        statuses = DefaultAppsManager.statuses()
        declined = DefaultAppsSettingsManager.declinedCategories()
        loading = false
    }

    LaunchedEffect(Unit) {
        if (!supported) {
            loading = false
            return@LaunchedEffect
        }
        DefaultAppsSettingsManager.ensureLoaded()
        refresh()
    }

    SettingsSection(
        title = "Default Apps",
        description = "Choose what BOSS opens when you click a link or double-click a file",
    ) {
        if (!supported) {
            UnsupportedNotice()
            return@SettingsSection
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = BossTheme.colors.ink,
            shape = RoundedCornerShape(8.dp),
            elevation = 0.dp,
            border = BorderStroke(1.dp, BossTheme.colors.line),
        ) {
            // One helper for both buttons: mark busy, claim, report, refresh. The
            // two differ only in what they claim and what the message calls it,
            // and keeping the sequence in one place is what stops the busy flag
            // and the spinner disagreeing.
            fun claim(
                busyId: String,
                what: String,
                clearDeclineFor: Collection<String> = emptyList(),
                block: suspend () -> ClaimOutcome,
            ) = scope.launch {
                busyCategoryId = busyId
                message = null
                // Pressing Set on a row is the user changing their mind, so the
                // refusal recorded when they dismissed the first-run offer has to
                // go - otherwise "Set all" would keep skipping a category they
                // have since asked for by hand.
                if (clearDeclineFor.isNotEmpty()) DefaultAppsSettingsManager.clearDeclined(clearDeclineFor)
                message = block().describe(what)
                busyCategoryId = null
                refresh()
            }

            Column(modifier = Modifier.padding(16.dp)) {
                CategoryList(
                    statuses = statuses,
                    busyCategoryId = busyCategoryId,
                    loading = loading,
                    onSet = { status ->
                        claim(
                            busyId = status.category.id,
                            what = status.category.displayName,
                            clearDeclineFor = listOf(status.category.id),
                        ) { DefaultAppsManager.claimOne(status.category) }
                    },
                )

                Spacer(modifier = Modifier.height(16.dp))

                SectionActions(
                    statuses = statuses,
                    declined = declined,
                    busy = busyCategoryId != null,
                    onRefresh = { scope.launch { refresh() } },
                    onSetAll = { toClaim ->
                        claim(busyId = "", what = "every type") { DefaultAppsManager.claimAll(toClaim) }
                    },
                )

                message?.let { text ->
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = text,
                        color = BossTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun CategoryList(
    statuses: List<DefaultAppStatus>,
    busyCategoryId: String?,
    loading: Boolean,
    onSet: (DefaultAppStatus) -> Unit,
) {
    if (loading && statuses.isEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = BossTheme.colors.signal,
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Checking...", color = BossTheme.colors.textSecondary, fontSize = 14.sp)
        }
        return
    }

    statuses.forEachIndexed { index, status ->
        if (index > 0) {
            Spacer(modifier = Modifier.height(12.dp))
            Divider(color = BossTheme.colors.line)
            Spacer(modifier = Modifier.height(12.dp))
        }
        CategoryRow(
            status = status,
            busy = busyCategoryId == status.category.id,
            // Disabled while ANY row is working, not just this one: the platform
            // calls are not concurrent-safe against each other (Windows opens a
            // Settings page, macOS walks 55 types) and two at once produces a
            // result nobody can read.
            enabled = busyCategoryId == null && !loading,
            onSet = { onSet(status) },
        )
    }
}

@Composable
private fun SectionActions(
    statuses: List<DefaultAppStatus>,
    declined: Set<String>,
    busy: Boolean,
    onRefresh: () -> Unit,
    onSetAll: (List<FileTypeCategory>) -> Unit,
) {
    // Refused categories are excluded, which is what DefaultAppsSettings'
    // `declinedCategories` was documented to do and did not: "Set all" claimed
    // exactly what the user had just said no to. A row's own Set button still
    // works and clears the refusal.
    val unclaimed =
        statuses
            .filterNot { it.state.isOurs }
            .map { it.category }
            .filterNot { it.id in declined }

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onRefresh,
            enabled = !busy,
            colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary),
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = "Refresh", modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Refresh", fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = { onSetAll(unclaimed) },
            enabled = !busy && unclaimed.isNotEmpty(),
            colors =
                ButtonDefaults.buttonColors(
                    backgroundColor = BossTheme.colors.signal,
                    contentColor = BossTheme.colors.onSignal,
                ),
        ) {
            Text(if (unclaimed.isEmpty()) "All set" else "Set all", fontSize = 13.sp)
        }
    }
}

@Composable
private fun UnsupportedNotice() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        backgroundColor = BossTheme.colors.ink,
        shape = RoundedCornerShape(8.dp),
        elevation = 0.dp,
        border = BorderStroke(1.dp, BossTheme.colors.line),
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Outlined.Warning,
                contentDescription = null,
                tint = BossTheme.colors.textSecondary,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "BOSS cannot read or change default applications on this system.",
                color = BossTheme.colors.textSecondary,
                fontSize = 13.sp,
            )
        }
    }
}

@Composable
private fun CategoryRow(
    status: DefaultAppStatus,
    busy: Boolean,
    enabled: Boolean,
    onSet: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = status.category.displayName,
                color = BossTheme.colors.textPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = status.category.description,
                color = BossTheme.colors.textMuted,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(modifier = Modifier.height(6.dp))
            StatusLine(status.state)
        }

        Spacer(modifier = Modifier.width(12.dp))

        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = BossTheme.colors.signal,
            )
        } else {
            TextButton(
                onClick = onSet,
                enabled = enabled && !status.state.isOurs,
                colors =
                    ButtonDefaults.textButtonColors(
                        contentColor = BossTheme.colors.signalText,
                        disabledContentColor = BossTheme.colors.textMuted,
                    ),
            ) {
                Text(
                    // "Repair" when a BOSS component holds the type: the user did
                    // set BOSS as their handler, they just got the wrong "BOSS",
                    // and "Set" would suggest they had not tried.
                    text =
                        when (status.state) {
                            is DefaultHandlerState.Ours -> "Default"
                            is DefaultHandlerState.OurEngine -> "Repair"
                            is DefaultHandlerState.Other -> "Set"
                        },
                    fontSize = 13.sp,
                )
            }
        }
    }
}

@Composable
private fun StatusLine(state: DefaultHandlerState) {
    val (icon, tint, text) =
        when (state) {
            is DefaultHandlerState.Ours -> {
                Triple(Icons.Outlined.CheckCircle, BossTheme.colors.ok, "BOSS opens these")
            }

            is DefaultHandlerState.OurEngine -> {
                Triple(
                    Icons.Outlined.Warning,
                    BossTheme.colors.alert,
                    "A BOSS component is registered instead of BOSS itself, so these open with no BOSS window",
                )
            }

            is DefaultHandlerState.Other -> {
                Triple(
                    Icons.Outlined.Cancel,
                    BossTheme.colors.textSecondary,
                    state.bundleId?.let { "Currently opened by $it" } ?: "No application is registered",
                )
            }
        }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            color = tint,
            fontSize = 12.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What to tell the user after a claim attempt. */
private fun ClaimOutcome.describe(what: String): String =
    when (this) {
        is ClaimOutcome.Claimed -> "BOSS now opens $what."
        is ClaimOutcome.NeedsUserAction -> instruction
        is ClaimOutcome.Failed -> message
    }
