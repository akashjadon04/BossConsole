package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsInfoRow
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsSlider
import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextMuted
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.components.settings.shared.SettingsToggle
import ai.rever.boss.focusmode.FocusModeSettingsManager
import androidx.compose.foundation.layout.*
import androidx.compose.material.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@Composable
fun FocusModeSettings() {
    val settings by FocusModeSettingsManager.currentSettings.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Local state for sliders
    var revealOffset by remember(settings) { mutableStateOf(settings.revealOffsetPx) }
    var revealDelay by remember(settings) { mutableStateOf(settings.revealDelayMs.toFloat()) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Main Toggle
        SettingsSection(title = "Focus Mode") {
            SettingsToggle(
                label = "Enable Focus Mode",
                checked = settings.enabled,
                onCheckedChange = { enabled ->
                    coroutineScope.launch {
                        FocusModeSettingsManager.updateSettings(
                            settings.copy(enabled = enabled),
                        )
                    }
                },
                description = "Clear the window chrome you pick below to maximize content area",
            )
        }

        // What stays visible
        SettingsSection(title = "What Stays Visible") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                InfoItem(text = "✓ Tab bar - for switching between open files")
                InfoItem(text = "✓ Main content panel - your primary work area")
                InfoItem(text = "✓ Window title bar - for window controls")
            }
        }

        // What gets hidden - one switch per window edge
        SettingsSection(title = "What Gets Hidden") {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsToggle(
                    label = "Top action bar",
                    checked = settings.hideTopBar,
                    onCheckedChange = { hide ->
                        coroutineScope.launch {
                            FocusModeSettingsManager.updateSettings(settings.copy(hideTopBar = hide))
                        }
                    },
                    description = "Project selector, settings, etc.",
                )
                SettingsToggle(
                    label = "Left sidebar",
                    checked = settings.hideLeftSidebar,
                    onCheckedChange = { hide ->
                        coroutineScope.launch {
                            FocusModeSettingsManager.updateSettings(settings.copy(hideLeftSidebar = hide))
                        }
                    },
                    description = "Plugin panels docked to the left edge",
                )
                SettingsToggle(
                    label = "Right sidebar",
                    checked = settings.hideRightSidebar,
                    onCheckedChange = { hide ->
                        coroutineScope.launch {
                            FocusModeSettingsManager.updateSettings(settings.copy(hideRightSidebar = hide))
                        }
                    },
                    description = "Plugin panels docked to the right edge",
                )
                SettingsToggle(
                    label = "Bottom status bar",
                    checked = settings.hideBottomBar,
                    onCheckedChange = { hide ->
                        coroutineScope.launch {
                            FocusModeSettingsManager.updateSettings(settings.copy(hideBottomBar = hide))
                        }
                    },
                )
                // Deliberately not gated on settings.enabled: you would have to turn focus
                // mode on, losing the top bar, before you could choose what it clears.
                // hides() gates on enabled anyway, so these are inert until it is on.
                if (settings.enabled && !settings.hidesAnything()) {
                    InfoItem(text = "Focus mode is on but hides nothing - pick at least one edge above.")
                }
            }
        }

        // Auto-reveal
        SettingsSection(title = "Auto-Reveal") {
            SettingsToggle(
                label = "Auto-Reveal on Hover",
                checked = settings.autoRevealEnabled,
                onCheckedChange = { enabled ->
                    coroutineScope.launch {
                        FocusModeSettingsManager.updateSettings(
                            settings.copy(autoRevealEnabled = enabled),
                        )
                    }
                },
                description = "Show hidden UI elements when mouse approaches window edges",
                enabled = settings.enabled,
            )

            if (settings.enabled && settings.autoRevealEnabled) {
                SettingsSlider(
                    label = "Reveal Sensitivity",
                    value = revealOffset,
                    onValueChange = { revealOffset = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            FocusModeSettingsManager.updateSettings(
                                settings.copy(revealOffsetPx = revealOffset),
                            )
                        }
                    },
                    valueRange = 5f..50f,
                    steps = 8,
                    valueDisplay = { "${it.toInt()} px" },
                    description = "Distance from window edge to trigger reveal",
                )

                SettingsSlider(
                    label = "Reveal Delay",
                    value = revealDelay,
                    onValueChange = { revealDelay = it },
                    onValueChangeFinished = {
                        coroutineScope.launch {
                            FocusModeSettingsManager.updateSettings(
                                settings.copy(revealDelayMs = revealDelay.toLong()),
                            )
                        }
                    },
                    valueRange = 0f..1000f,
                    steps = 9,
                    valueDisplay = { if (it == 0f) "Instant" else "${it.toInt()} ms" },
                    description = "Time to hover at edge before UI reveals",
                )
            }
        }

        // Keyboard shortcut
        SettingsSection(title = "Keyboard Shortcut") {
            SettingsInfoRow(
                label = "Toggle Focus Mode",
                value = "Cmd+Shift+F / Ctrl+Shift+F",
                description = "Customize in Settings > Keyboard Shortcuts",
            )
        }
    }
}

@Composable
private fun InfoItem(text: String) {
    Text(
        text = text,
        fontSize = 13.sp,
        color = TextSecondary,
        lineHeight = 20.sp,
    )
}
