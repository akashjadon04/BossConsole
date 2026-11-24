package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.DependentRestartCoordinator
import ai.rever.boss.components.plugin.DependentRestartDeclinedException
import ai.rever.boss.components.plugin.DependentRestartEventBus
import ai.rever.boss.components.plugin.DynamicPluginManager
import ai.rever.boss.plugin.api.PluginUnloadIntent
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Removes a plugin: unload it, then delete what it left behind.
 *
 * Detached from the caller for the same reason installs are. The prompt runs on a window's
 * `rememberCoroutineScope`, and a cancellation landing between the unload and the cleanup leaves the
 * jar and its `installed.json` row on disk after the panels have already been torn down, so the
 * plugin comes back at the next launch and the user's "uninstall" reads as a lie. Coalescing per
 * plugin id also stops two windows racing the same removal.
 */
object PluginRemoval {
    private val logger = BossLogger.forComponent("PluginRemoval")

    private val REMOVAL_SCOPE = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val DETACHED_REMOVALS = KeyedDetachedJobs<String, Result<Unit>>(REMOVAL_SCOPE)

    suspend fun remove(
        pluginId: String,
        jarPath: String,
        manager: DynamicPluginManager,
    ): Result<Unit> =
        DETACHED_REMOVALS.run(
            key = pluginId,
            onDetachedFailure = { error ->
                logger.error(LogCategory.SYSTEM, "Detached plugin removal failed", error = error)
            },
        ) {
            // This path does NOT go through PluginLoaderDelegateImpl - it calls the manager
            // directly - so it needs its own copy of the question, or the host's own Uninstall
            // would still hard-refuse while the Toolbox's asked. Only when the manifest allows
            // the unload at all: a `canUnload = false` plugin is refused whatever the answer, so
            // asking would be a dialog with one real outcome. (The menu gates on that too, but
            // this function is reachable from the deep-link handler as well.)
            val info = manager.getPluginInfo(pluginId)
            val dependents =
                if (info?.manifest?.canUnload == false) emptyList() else manager.dependentsOf(pluginId)
            val confirmed =
                dependents.isEmpty() ||
                    DependentRestartEventBus.ask(
                        DependentRestartCoordinator.promptFor(
                            targetPluginId = pluginId,
                            targetDisplayName = info?.manifest?.displayName ?: pluginId,
                            intent = PluginUnloadIntent.REMOVE,
                            dependents = dependents,
                        ),
                    )
            if (!confirmed) {
                return@run Result.failure(DependentRestartDeclinedException(pluginId))
            }

            // Forced only once the user has agreed to the consequence the veto exists to warn
            // about. With no dependents this is the unchanged non-forced path, so the manifest
            // gate and the unload-aware checks still apply as they always did.
            val unloaded = manager.uninstallPlugin(pluginId, force = dependents.isNotEmpty())
            if (unloaded.isFailure) {
                return@run unloaded
            }
            // Only once the plugin is unloaded: deleting a jar out from under a live classloader is
            // how you get NoClassDefFoundError from code that is still running.
            PluginArtifactCleanup.remove(pluginId, jarPath)
            // Nothing is coming back, so the dependents are restarted now rather than recorded:
            // each is holding a handle into a classloader that has just closed, and a restart
            // makes it re-resolve to null - the truth about what is now installed.
            DependentRestartCoordinator.restartNow(dependents.map { it.pluginId })
            Result.success(Unit)
        }

    /**
     * Why [pluginId] cannot usefully be removed, or null when it can.
     *
     * The manifest gate (`systemPlugin || !canUnload`) covers the plugins the manager refuses to
     * unload. This covers a different case: a plugin whose jar also sits in the bundled directory is
     * re-copied by `copyBundledPluginsToPluginDir` on the next launch whenever no jar for its id is
     * in the plugins directory. Uninstalling one of those succeeds and then quietly undoes itself, so
     * it is better to say so than to let the plugin reappear.
     */
    fun removalVeto(
        pluginId: String,
        bundledDir: java.io.File,
        readManifestId: (String) -> String? = { path ->
            runCatching {
                ai.rever.boss.plugin.loader.PluginManifestReader
                    .readFromJar(path)
                    .pluginId
            }.getOrNull()
        },
    ): String? {
        val jars =
            runCatching {
                bundledDir.takeIf { it.isDirectory }?.listFiles { f -> f.isFile && f.extension == "jar" }
            }.getOrNull() ?: return null
        val bundled = jars.any { readManifestId(it.absolutePath) == pluginId }
        return if (bundled) "ships with BOSS and would be restored at the next launch" else null
    }
}
