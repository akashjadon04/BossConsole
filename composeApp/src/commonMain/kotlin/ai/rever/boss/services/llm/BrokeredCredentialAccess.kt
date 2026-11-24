package ai.rever.boss.services.llm

import ai.rever.boss.plugin.api.BrokeredCredentialProvider
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

private val logger = BossLogger.forComponent("BrokeredCredentialAccess")

/**
 * Holds the host's credential-broker implementation so `DefaultPlugin` can serve it.
 *
 * A holder rather than a direct reference because the implementation exchanges a Supabase
 * session over HTTP and so lives in `desktopMain`, while `DefaultPlugin` - the class that
 * has to expose it on `PluginContext` - is in `commonMain`. Desktop startup registers it.
 *
 * Null until then, and null on any build that does not register one, which is the same
 * "provider may be absent" contract every other member of `PluginContext` has.
 */
object BrokeredCredentialAccess {
    @Volatile
    private var provider: BrokeredCredentialProvider? = null

    /** Called once from desktop startup. */
    fun initialize(implementation: BrokeredCredentialProvider) {
        provider = implementation
        logger.debug(LogCategory.SYSTEM, "BrokeredCredentialAccess initialized")
    }

    fun current(): BrokeredCredentialProvider? = provider
}
