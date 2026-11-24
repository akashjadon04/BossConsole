package ai.rever.boss.components.settings.sections

import ai.rever.boss.components.settings.shared.SettingsInfoRow
import ai.rever.boss.components.settings.shared.SettingsSection
import ai.rever.boss.components.settings.shared.SettingsTheme.SurfaceColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextMuted
import ai.rever.boss.components.settings.shared.SettingsTheme.TextSecondary
import ai.rever.boss.config.ChromiumFlagKeys
import ai.rever.boss.config.ChromiumFlagsSettings
import ai.rever.boss.config.ChromiumFlagsSettingsManager
import ai.rever.boss.config.JxBrowserConfig
import ai.rever.boss.plugin.browser.FluckEngine
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The read-only half of Settings > Browser Engine: what the engine is running with now,
 * and what it would run with after a restart.
 *
 * A separate file from the flag editors because it is a different kind of thing — a report
 * derived from the settings, with no controls and no state of its own. It is also the answer
 * to "did my setting do anything", which is the question the editors cannot answer.
 *
 * Both are shown because either alone is misleading: the active list cannot explain what
 * a setting just changed, and a preview alone reads as though it were already in effect.
 */
@Composable
internal fun EffectiveCommandLineSection(
    settings: ChromiumFlagsSettings,
    os: String,
    arch: String,
) {
    // Container detection reads /proc and /.dockerenv, so it is resolved off the UI thread.
    // Whether this machine is a container does not change between launches, so one read is
    // enough for both the active and the preview line.
    val inContainer by
        produceState(initialValue = false) {
            value = withContext(Dispatchers.IO) { FluckEngine.runningInContainer() }
        }

    val nextLaunch = remember(settings, os, arch, inContainer) { nextLaunchSwitches(settings, os, arch, inContainer) }

    val active by FluckEngine.lastAppliedSwitchesFlow.collectAsState()
    val diskCacheMb by FluckEngine.lastDiskCacheMbFlow.collectAsState()

    SettingsSection(
        title = "Effective Chromium command line",
        description = "Read-only. The switches the engine is given, before Chromium applies its own defaults.",
    ) {
        SwitchList(
            label = "Active this session",
            switches = active,
            emptyNote =
                "The browser engine has not started yet this session, so no switches have been applied. " +
                    "Open a browser tab to populate this.",
        )
        Spacer(modifier = Modifier.height(8.dp))
        SwitchList(
            label = "After the next restart",
            switches = nextLaunch,
            emptyNote = "No switches - every flag that produces one is turned off.",
        )
        Spacer(modifier = Modifier.height(8.dp))
        // Resolved the way the NEXT LAUNCH will resolve it: env first, then the setting. Reading
        // `settings.renderingMode` directly promised a change that would not happen whenever the
        // environment owned the key - in the one section whose stated purpose is that it does not
        // lie. previewValue exists for exactly this.
        val nextMode = nextRenderingMode(settings)
        SettingsInfoRow(
            label = "Rendering mode",
            // The live value, latched for this process, not the pending setting - this block is
            // about what IS, and the preview above covers what will be.
            value = JxBrowserConfig.renderingMode.name,
            description =
                if (nextMode != JxBrowserConfig.renderingMode) {
                    "Changes to ${nextMode.name} after a restart."
                } else {
                    null
                },
        )
        Spacer(modifier = Modifier.height(8.dp))
        SettingsInfoRow(
            label = "HTTP disk cache",
            value = diskCacheMb?.let { "$it MB" } ?: "not applied yet",
            description = "Set through the JxBrowser API rather than a switch, so it does not appear above.",
        )
        if (inContainer) {
            Spacer(modifier = Modifier.height(8.dp))
            SettingsInfoRow(
                label = "Container detected",
                value = "yes",
                description =
                    "Container-only switches are added and the Chromium sandbox is turned off " +
                        "automatically, because it usually cannot start without user namespaces.",
            )
        }
    }
}

/** One labelled, horizontally scrollable monospace block of switches. */
@Composable
private fun SwitchList(
    label: String,
    switches: List<String>,
    emptyNote: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceColor)
                .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = label, color = TextSecondary, fontSize = 13.sp)
        if (switches.isEmpty()) {
            Text(
                text = emptyNote,
                color = TextMuted,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            // One switch per line rather than a single joined command line: a value containing
            // commas (--enable-features=A,B) is impossible to read wrapped, and horizontal scroll
            // keeps the page itself from scrolling sideways.
            Column(
                modifier = Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                switches.forEach { switch ->
                    Text(text = switch, color = TextMuted, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

/**
 * The rendering mode the next launch will resolve to, env first then the setting.
 *
 * Shared so the preview, the Graphite default and the mode row cannot disagree about which mode
 * they are describing — the drift this fixes came from two of them reading the LIVE mode instead.
 */
internal fun nextRenderingMode(settings: ChromiumFlagsSettings): com.teamdev.jxbrowser.engine.RenderingMode =
    JxBrowserConfig.resolveRenderingMode(
        ChromiumFlagsSettingsManager.previewValue(settings, ChromiumFlagKeys.RENDERING_MODE),
        System.getProperty("os.name").orEmpty().lowercase(),
    )

/**
 * The switch list the next launch would produce from [settings].
 *
 * Built by calling the engine's own [FluckEngine.performanceSwitchesFor] with the same
 * resolution the engine applies, rather than by describing the switches again here — a
 * second description is a second thing to keep in sync, and the whole value of this panel
 * is that it does not lie.
 *
 * Values that have a config key go through [ChromiumFlagsSettingsManager.previewValue] so
 * an environment variable shows up as winning, exactly as it will at boot.
 */
private fun nextLaunchSwitches(
    settings: ChromiumFlagsSettings,
    os: String,
    arch: String,
    inContainer: Boolean,
): List<String> {
    val cap =
        FluckEngine.renderCapSwitch(
            ChromiumFlagsSettingsManager.previewValue(settings, ChromiumFlagKeys.RENDERER_PROCESS_LIMIT),
        )
    val extras =
        FluckEngine.parseExtraSwitches(
            ChromiumFlagsSettingsManager.previewValue(settings, ChromiumFlagKeys.EXTRA_SWITCHES),
        )
    // Same resolver the engine uses, so the preview shows the mode-dependent default rather
    // than reading an unset value as "off".
    // resolveSkiaGraphite takes the mode the NEXT LAUNCH will use, not JxBrowserConfig.renderingMode,
    // which is latched for this process. Using the live mode made the preview drift on exactly the
    // path the mode-dependence exists to protect: select Off-screen and this still listed
    // SkiaGraphite, while the next launch emits none — and the reverse previewed no Graphite while
    // the next boot added the switch that was observed blanking pages.
    val graphite =
        FluckEngine.resolveSkiaGraphite(
            ChromiumFlagsSettingsManager.previewValue(settings, ChromiumFlagKeys.SKIA_GRAPHITE),
            nextRenderingMode(settings),
        )
    return FluckEngine.performanceSwitchesFor(
        os = os,
        arch = arch,
        inContainer = inContainer,
        extraSwitches = listOfNotNull(cap) + extras,
        toggles = FluckEngine.SwitchToggles.from(settings).copy(skiaGraphite = graphite),
    )
}
