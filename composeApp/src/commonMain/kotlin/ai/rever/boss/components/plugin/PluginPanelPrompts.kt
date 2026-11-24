package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PanelId

/**
 * "You are not running the released build - install it?", raised by clicking a panel's build tag or
 * its version row in the overflow menu.
 *
 * [storeVersion] is null when there is nothing to install, in which case [note] says why and the
 * dialog offers only a way out. A plugin built locally and never published is the ordinary case
 * there, not an error.
 */
data class StoreVersionPrompt(
    val pluginId: String,
    val displayName: String,
    /** What is running now, suffixed - e.g. `1.0.3-debug+1754890231447`. */
    val runningVersion: String,
    val storeVersion: String?,
    /** The store's download URL, carried through so the install can record where the jar came from. */
    val storeSourceUrl: String? = null,
    val note: String? = null,
)

/**
 * "Remove this plugin?", raised from a panel's overflow menu.
 *
 * [panelIds] and [jarPath] are captured when the prompt is raised, which is deliberately BEFORE the
 * uninstall runs: unregistering a plugin clears the registration tracker, and dropping its state
 * clears the jar path, so neither can be looked up afterwards - and both are needed to close the
 * open panels and delete the file.
 */
data class PluginUninstallPrompt(
    val pluginId: String,
    val displayName: String,
    val version: String,
    val jarPath: String,
    val panelIds: Set<PanelId>,
)
