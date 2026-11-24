package ai.rever.boss.components.home

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.registries.RegistryAccess
import ai.rever.boss.plugin.api.PanelRegistry
import ai.rever.boss.plugin.api.TabRegistry
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.StateFlow

/**
 * The registries the home screen derives its tool grid from, reached as composition locals.
 *
 * **Locals rather than parameters because the screen has two mount points.** It renders in an
 * empty split panel, where `BossAppState` is a few frames up the tree, and inside a browser tab
 * showing about:blank, where the caller is a plugin holding a `DashboardContentProvider` and has
 * no access to host state at all. Threading registries down as parameters is what produced the
 * defect this rewrite removes: the second caller could not supply them, so it passed eleven
 * empty lambdas and most of the screen silently did nothing.
 *
 * Every default is empty rather than throwing, so the screen degrades to its host actions in a
 * preview or a test scene instead of crashing.
 */
val LocalTabRegistry = compositionLocalOf<TabRegistry?> { null }

val LocalPanelRegistry = compositionLocalOf<PanelRegistry?> { null }

/**
 * The plugin manager's entries, for deciding which store plugins are already installed.
 *
 * The **flow**, not a collected snapshot. Providing the snapshot meant collecting it in
 * `BossAppCompositionLocals`, so every plugin state transition invalidated the window scaffold and
 * re-ran its `CompositionLocalProvider` body - dozens of times during startup with 33 plugins, and
 * again on every install, enable and reload. Handing over the flow confines that to the one screen
 * that reads it.
 *
 * The only question asked of it is which ids are installed, and the answer runs through
 * `PluginDependencyResolution.installedAndOnDisk` - the codebase's single definition of that, which
 * AGENTS.md records as having broken the dependency prompt once when two callers disagreed.
 */
val LocalPluginStates = compositionLocalOf<StateFlow<Map<String, DynamicPluginInfo>>?> { null }

/** The current user's RBAC snapshot, so gated tools appear and disappear live. */
val LocalRegistryAccess = compositionLocalOf { RegistryAccess() }
