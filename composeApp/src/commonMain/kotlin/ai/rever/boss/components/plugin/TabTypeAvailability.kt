package ai.rever.boss.components.plugin

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.plugin.api.PluginState
import ai.rever.boss.plugin.api.TabRegistry
import ai.rever.boss.plugin.api.TabTypeId
import ai.rever.boss.plugin.sandbox.ui.PluginCrashRegistry
import ai.rever.boss.utils.awaitRegistryCondition
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Makes sure the plugin that renders a tab type is running before something tries
 * to open one, and offers to fix it when it is not.
 *
 * **The behaviour this replaces.** `BossMainWindowPanel.addTab` logs "Dropped tab
 * - no factory registered for its type" and returns -1. Every caller ignored that
 * return value, so with the browser plugin absent the OS could hand BOSS a link
 * and *nothing at all* appeared - no tab, no error, no dialog. The user's report
 * was "BOSS is my default browser and clicking a link does nothing", which is
 * exactly what it looks like from outside.
 *
 * The wait matters as much as the prompt. At a cold start the plugins have not
 * registered yet - `WorkspaceApplier.awaitTabTypes` exists for the same reason -
 * so asking "is the editor plugin here?" the instant a queued file open drains
 * would raise a false alarm on every launch. So: check, wait a bounded while,
 * and only then conclude the plugin is genuinely absent.
 */
object TabTypeAvailability {
    private val logger = BossLogger.forComponent("TabTypeAvailability")

    /**
     * How long to wait for the plugin after the user has been asked.
     *
     * Far longer than [ai.rever.boss.utils.PLUGIN_REGISTRATION_TIMEOUT_MS],
     * because what is being waited on is different: the first wait is for plugin
     * startup, this one is for a person to read a dialog, press Install and for a
     * jar to download over whatever connection they have. Bounded rather than
     * indefinite so a prompt nobody ever answers cannot pin the deferred open
     * forever.
     */
    private const val USER_DECISION_TIMEOUT_MS = 5 * 60 * 1000L

    /**
     * True when [typeId] can be opened, waiting and prompting if it cannot.
     *
     * Suspends. Callers run it off the path that has to stay responsive - see
     * `SplitViewState.requireTabTypeThen`, which launches it and performs the
     * open in the continuation.
     *
     * @param purpose what the user was trying to do, for the dialog ("Opening
     *   README.md"). Never a plugin id or a type id: the point of the prompt is to
     *   connect the missing plugin to the thing the user just did.
     * @param bus injected for tests.
     */
    suspend fun require(
        registry: TabRegistry,
        typeId: TabTypeId,
        purpose: String,
        bus: MissingHandlerPluginBus = MissingHandlerPluginEventBus,
    ): Boolean {
        if (registry.isRegistered(typeId)) return true

        // Bounded wait for plugin startup. Without it, every file the OS queues
        // during a cold start would prompt.
        //
        // With a status message, because the wait is up to 15 seconds and the user
        // has just double-clicked something: silence for 15 seconds looks exactly
        // as dropped as the bug this whole path replaces, only slower. Shown for
        // the wait only, and replaced by the dialog or the opened tab.
        StatusMessageManager.showMessage("Waiting for the plugin that opens ${TabTypePlugins.describe(typeId)}...")
        val appearedOnItsOwn =
            awaitRegistryCondition(
                registry::addChangeListener,
                registry::removeChangeListener,
            ) { registry.isRegistered(typeId) }
        if (appearedOnItsOwn) return true

        val pluginId = TabTypePlugins.pluginFor(typeId)
        if (pluginId == null) {
            // A plugin's own tab type that its plugin never registered. Nothing
            // to offer: the host does not know who owns it, and guessing would
            // offer to install the wrong thing.
            logger.warn(
                LogCategory.UI,
                "Tab type is not registered and no plugin is known for it",
                mapOf("tabType" to typeId.typeId, "purpose" to purpose),
            )
            return false
        }

        if (bus.wasDeclined(pluginId)) {
            logger.debug(
                LogCategory.UI,
                "Not asking about a plugin the user declined this session",
                mapOf("plugin" to pluginId),
            )
            return false
        }

        val prompt = promptFor(typeId, pluginId, purpose)
        if (prompt == null) {
            logger.warn(
                LogCategory.UI,
                "Cannot offer to fix a missing tab-type plugin",
                mapOf("plugin" to pluginId, "tabType" to typeId.typeId),
            )
            return false
        }

        logger.info(
            LogCategory.UI,
            "Asking about a missing tab-type plugin",
            mapOf("plugin" to pluginId, "tabType" to typeId.typeId, "remedy" to prompt.missing.remedy.name),
        )
        bus.report(prompt)

        // The dialog does not call back. Installing or enabling the plugin
        // registers the tab type, which fires the registry's change listeners,
        // which completes this wait - so the file the user double-clicked opens
        // as a consequence of the fix rather than needing a second attempt.
        val resolved =
            awaitRegistryCondition(
                registry::addChangeListener,
                registry::removeChangeListener,
                timeoutMs = USER_DECISION_TIMEOUT_MS,
            ) { registry.isRegistered(typeId) }

        if (!resolved) {
            logger.info(
                LogCategory.UI,
                "Giving up on a deferred open; the plugin never became available",
                mapOf("plugin" to pluginId, "tabType" to typeId.typeId),
            )
        }
        return resolved
    }

    /**
     * Builds the prompt, choosing between Install and Enable, or null when
     * neither can be offered.
     *
     * Null when there is no live `DynamicPluginManager` to act on - which happens
     * during shutdown - because a dialog whose button cannot do anything is worse
     * than no dialog.
     */
    private fun promptFor(
        typeId: TabTypeId,
        pluginId: String,
        purpose: String,
    ): MissingHandlerPluginPrompt? {
        val manager = DynamicPluginManager.anyActiveManager() ?: return null

        val installed =
            PluginDependencyResolution.installedAndOnDisk(
                states = manager.pluginStates.value,
                // The same two predicates `MissingDependencyReporter.forManager`
                // supplies, so this and the dependency prompt cannot disagree
                // about what "installed" means - AGENTS.md records that
                // disagreement having broken the dependency prompt once already.
                exists = { File(it).isFile },
                isIncompatible = { PluginCrashRegistry.isIncompatible(it) },
            )

        val info = manager.pluginStates.value[pluginId]
        val remedy =
            when {
                // Installed and switched off: enabling is the fix, and installing
                // would refuse ("Plugin already loaded") or rewrite the same jar
                // and change nothing.
                pluginId in installed && info?.state == PluginState.DISABLED -> {
                    MissingHandlerRemedy.ENABLE
                }

                pluginId in installed -> {
                    // Present, on disk, not disabled, and its tab type still is
                    // not registered after the wait. Enabling is a no-op and
                    // installing is wrong; there is nothing honest to offer, so
                    // say so in the log rather than showing a button that cannot
                    // work.
                    logger.warn(
                        LogCategory.UI,
                        "Tab-type plugin is installed and not disabled but registered no tab type",
                        mapOf("plugin" to pluginId, "state" to (info?.state?.name ?: "unknown")),
                    )
                    return null
                }

                else -> {
                    MissingHandlerRemedy.INSTALL
                }
            }

        val missing =
            MissingHandlerPlugin(
                purpose = purpose,
                capability = TabTypePlugins.describe(typeId),
                tabTypeId = typeId.typeId,
                pluginId = pluginId,
                remedy = remedy,
            )

        // The same installer the dependency prompt and the home tool grid use,
        // through the factory `main.kt` injects. Null before startup finishes and
        // in tests that never wire it, which is why INSTALL reports a failure the
        // dialog can show rather than throwing.
        val installer = MissingPluginOffer.installerFactory?.invoke(manager)

        return MissingHandlerPluginPrompt(
            missing = missing,
            resolve = {
                when (remedy) {
                    MissingHandlerRemedy.ENABLE -> {
                        manager.enablePlugin(pluginId)
                    }

                    MissingHandlerRemedy.INSTALL -> {
                        installer?.install(pluginId)
                            ?: Result.failure(IllegalStateException("The plugin store is not available."))
                    }
                }
            },
            displayName = { installer?.displayNameFor(pluginId) },
        )
    }
}
