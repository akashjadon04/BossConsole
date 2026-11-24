package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.utils.DefaultBrowserManager
import ai.rever.boss.utils.DefaultHandlerState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/**
 * Default Browser section for BOSS Console settings
 *
 * Allows users to:
 * - Check who currently handles http/https
 * - Set BOSS as the default browser, or repair the case below
 * - View platform-specific instructions
 *
 * **It reads [DefaultHandlerState], not a boolean, for the same reason
 * `Settings > Default Apps` does.** A machine that installed BOSS before the
 * branded Chromium engine stopped declaring `CFBundleURLTypes` has a second app
 * called "BOSS" holding http, https and `public.html`
 * (`~/.boss/boss-chromium/BOSS.app`, id `ai.rever.boss.browser`), and links open
 * a bare rendering engine with no window, no tabs and no session. Flattened to a
 * boolean that is indistinguishable from Safari being the default, so this card
 * said "BOSS is not your default browser" - the wrong story to tell somebody who
 * did set it, and it sent them to a System Settings list with two identical BOSS
 * entries where picking either looks the same.
 *
 * `Settings > Default Apps` has reported that case since it shipped. This card is
 * the older surface for the same two categories and is the one a user reaches
 * from `Settings > Browser`, so leaving it flattened meant the two screens
 * contradicted each other about the same machine.
 */
@Composable
fun DefaultBrowserSection() {
    val platformName = DefaultBrowserManager.getPlatformName()
    var state by remember { mutableStateOf<DefaultHandlerState?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showSuccessDialog by remember { mutableStateOf(false) }
    var showInstructionsDialog by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()

    // One read path for the mount, the Refresh button and the re-read after a
    // successful set. It was three copies of the same fold, which is how the
    // "assume success" line below got out of step with what the OS actually holds.
    suspend fun refresh() {
        isLoading = true
        errorMessage = null

        val result = DefaultBrowserManager.browserHandlerState()
        isLoading = false

        result.fold(
            onSuccess = { state = it },
            onFailure = { error ->
                errorMessage = error.message
                state = null
            },
        )
    }

    // Check status on mount
    LaunchedEffect(Unit) {
        refresh()
    }

    SettingsSection(
        title = "Default Browser",
        description = "Make BOSS your default web browser",
    ) {
        // Status Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = BossTheme.colors.ink,
            shape = RoundedCornerShape(8.dp),
            elevation = 0.dp,
            border = BorderStroke(1.dp, BossTheme.colors.line),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // Status Display
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            text = "Status",
                            color = BossTheme.colors.textSecondary,
                            fontSize = 13.sp,
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        when {
                            isLoading -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = BossTheme.colors.signal,
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Checking...",
                                        color = BossTheme.colors.textSecondary,
                                        fontSize = 14.sp,
                                    )
                                }
                            }

                            errorMessage != null -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Error,
                                        contentDescription = "Error",
                                        tint = BossTheme.colors.alert,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Error checking status",
                                        color = BossTheme.colors.alert,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }

                            state is DefaultHandlerState.OurEngine -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Warning,
                                        contentDescription = "Needs repair",
                                        tint = BossTheme.colors.alert,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "A BOSS component holds this, so links open with no BOSS window",
                                        color = BossTheme.colors.textPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }

                            state?.isOurs == true -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.CheckCircle,
                                        contentDescription = "Default",
                                        tint = BossTheme.colors.ok,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "BOSS is your default browser",
                                        color = BossTheme.colors.textPrimary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }

                            state != null -> {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Outlined.Cancel,
                                        contentDescription = "Not Default",
                                        tint = BossTheme.colors.textSecondary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "BOSS is not your default browser",
                                        color = BossTheme.colors.textSecondary,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }

                    // Action Buttons
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Refresh button
                        TextButton(
                            onClick = { coroutineScope.launch { refresh() } },
                            colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary),
                        ) {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = "Refresh",
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Refresh", fontSize = 13.sp)
                        }

                        // Set as default button
                        TextButton(
                            onClick = {
                                coroutineScope.launch {
                                    isLoading = true
                                    errorMessage = null

                                    val result = DefaultBrowserManager.setAsDefaultBrowser()
                                    isLoading = false

                                    result.fold(
                                        onSuccess = { wasSetProgrammatically ->
                                            if (wasSetProgrammatically) {
                                                // Re-read rather than assume Ours. The call reports
                                                // that every claim was accepted, which is not the
                                                // same as the OS still holding it a moment later -
                                                // and assuming was what let this card disagree with
                                                // Default Apps about the same machine.
                                                refresh()
                                                showSuccessDialog = true
                                            } else {
                                                // User action required (Windows)
                                                showInstructionsDialog = true
                                            }
                                        },
                                        onFailure = { error ->
                                            errorMessage = error.message
                                        },
                                    )
                                }
                            },
                            enabled = !isLoading && state?.isOurs != true,
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = BossTheme.colors.signalText,
                                    disabledContentColor = BossTheme.colors.textSecondary,
                                ),
                        ) {
                            if (isLoading) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = BossTheme.colors.signal,
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Setting...", fontSize = 13.sp)
                            } else {
                                val repairing = state is DefaultHandlerState.OurEngine
                                Icon(
                                    if (repairing) Icons.Outlined.Build else Icons.AutoMirrored.Outlined.OpenInNew,
                                    contentDescription = if (repairing) "Repair" else "Set",
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                // Same call either way: claiming http, https and public.html for
                                // BOSS is what both setting and repairing amount to. Only the label
                                // differs, because "Set as Default" reads as a no-op to somebody
                                // who already set it and is looking at a card that says so.
                                Text(if (repairing) "Repair" else "Set as Default", fontSize = 13.sp)
                            }
                        }
                    }
                }

                // Error message
                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Error: $errorMessage",
                        color = BossTheme.colors.alert,
                        fontSize = 12.sp,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Platform-specific info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            backgroundColor = BossTheme.colors.signal.copy(alpha = 0.1f),
            shape = RoundedCornerShape(6.dp),
            elevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    Icons.Outlined.Info,
                    contentDescription = "Info",
                    tint = BossTheme.colors.signalText,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "Platform: $platformName",
                        fontSize = 12.sp,
                        color = BossTheme.colors.textPrimary,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text =
                            when (platformName) {
                                "macOS" -> "BOSS will attempt to set itself as default automatically"
                                "Windows" -> "Windows requires manual selection in Settings"
                                else -> "Uses XDG standards for Linux desktop environments"
                            },
                        fontSize = 11.sp,
                        color = BossTheme.colors.textSecondary,
                    )
                }
            }
        }
    }

    // Success Dialog
    if (showSuccessDialog) {
        BossAlertDialog(
            onDismissRequest = { showSuccessDialog = false },
            title = {
                Text(
                    "Success",
                    color = BossTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Text(
                    "BOSS has been set as your default web browser. Links will now open in BOSS.",
                    color = BossTheme.colors.textSecondary,
                    fontSize = 13.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { showSuccessDialog = false }) {
                    Text("OK", color = BossTheme.colors.signalText, fontSize = 13.sp)
                }
            },
            backgroundColor = BossTheme.colors.panel,
            contentColor = BossTheme.colors.textPrimary,
        )
    }

    // Instructions Dialog (platform-aware)
    if (showInstructionsDialog) {
        BossAlertDialog(
            onDismissRequest = { showInstructionsDialog = false },
            title = {
                Text(
                    when (platformName) {
                        "macOS" -> "Complete Setup in System Settings"
                        "Windows" -> "Complete Setup in Windows Settings"
                        else -> "Complete Setup in System Settings"
                    },
                    color = BossTheme.colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            },
            text = {
                Column {
                    Text(
                        when (platformName) {
                            "macOS" -> "System Settings has been opened. Please complete these steps:"
                            "Windows" -> "Windows Settings has been opened. Please complete these steps:"
                            else -> "Please complete these steps in your system settings:"
                        },
                        color = BossTheme.colors.textSecondary,
                        fontSize = 13.sp,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        when (platformName) {
                            "macOS" -> {
                                "1. Find \"Default web browser\" in Desktop & Dock\n" +
                                    "2. Click the dropdown menu\n" +
                                    "3. Select \"BOSS Console\" from the list"
                            }

                            "Windows" -> {
                                "1. Scroll down to \"Web browser\"\n" +
                                    "2. Click on the current browser\n" +
                                    "3. Select \"BOSS Console\" from the list\n" +
                                    "4. Close Settings"
                            }

                            else -> {
                                "1. Open \"Default Applications\" in your desktop settings\n" +
                                    "2. Find \"Web Browser\"\n" +
                                    "3. Select \"BOSS Console\" from the list"
                            }
                        },
                        color = BossTheme.colors.textPrimary,
                        fontSize = 13.sp,
                        lineHeight = 22.sp,
                    )
                    // Only when a BOSS component holds the role: the list the user is
                    // being sent to then contains TWO entries named BOSS, and picking
                    // the wrong one repeats the state they are trying to leave. The
                    // engine bundle stopped declaring these types, but an install that
                    // has not re-downloaded the engine yet still shows both.
                    if (state is DefaultHandlerState.OurEngine) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "If two entries are both named BOSS, the one to pick is the application " +
                                "itself, not the browser component. Reinstalling the browser engine in " +
                                "Settings > Browser Engine removes the duplicate.",
                            color = BossTheme.colors.textPrimary,
                            fontSize = 12.sp,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "After completing these steps, click \"Refresh\" to verify.",
                        color = BossTheme.colors.textSecondary,
                        fontSize = 12.sp,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showInstructionsDialog = false }) {
                    Text("Got it", color = BossTheme.colors.signalText, fontSize = 13.sp)
                }
            },
            backgroundColor = BossTheme.colors.panel,
            contentColor = BossTheme.colors.textPrimary,
        )
    }
}
