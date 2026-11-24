package ai.rever.boss.llm

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory

/**
 * Serves credential brokers to plugins.
 *
 * The host half of `BrokeredCredentialProvider`. A plugin names a broker by id and gets back
 * the downstream credential; the Supabase session stays in this process, which is the point -
 * nothing on `PluginContext` exposes it, and this keeps that boundary rather than widening it.
 */
internal object BrokeredCredentialProviderImpl : ai.rever.boss.plugin.api.BrokeredCredentialProvider {
    private val logger = BossLogger.forComponent("BrokeredCredentials")

    override fun availableBrokers(): List<ai.rever.boss.plugin.api.BrokerInfo> {
        val signedIn = CredentialBrokerClient.isSignedIn()
        return CredentialBrokers.all().map { broker ->
            ai.rever.boss.plugin.api.BrokerInfo(
                id = broker.id,
                displayName = broker.displayName,
                // Availability is "could this work right now", which for every broker here
                // means a signed-in session. A plugin can then explain rather than offering
                // an action that can only fail.
                available = signedIn,
                scopedTo = broker.scopedTo,
            )
        }
    }

    override suspend fun exchange(brokerId: String): Result<ai.rever.boss.plugin.api.BrokeredCredential> =
        CredentialBrokerClient
            .exchange(brokerId)
            .map { issued ->
                ai.rever.boss.plugin.api.BrokeredCredential(
                    token = issued.token,
                    refreshAfterSeconds = issued.refreshAfterSeconds,
                    expiresAt = issued.expiresAt,
                )
            }.onFailure { error ->
                // The token is never logged; the reason is, because "the provider says not
                // configured" is otherwise unexplainable from the outside.
                logger.info(
                    LogCategory.SYSTEM,
                    "Broker exchange failed",
                    mapOf("broker" to brokerId, "reason" to (error.message ?: "unknown")),
                )
            }
}
