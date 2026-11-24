package ai.rever.boss.llm

import ai.rever.boss.utils.SingleInstanceManager

/**
 * Headless credential-helper entrypoint invoked by Codex.
 *
 * stdout contains only the short-lived credential. The BOSS/Supabase session and the
 * CoreWeave credential are never printed or returned.
 *
 * The exchange itself is not here: it lives in [CredentialBrokerClient], shared with the
 * plugin-facing `BrokeredCredentialProvider`, so the endpoint and the session handling
 * exist once rather than twice.
 */
object RisaLlmTokenCommand {
    private const val COMMAND = "llm-token"

    fun isRequested(args: Array<String>): Boolean = args.size == 1 && args[0] == COMMAND

    fun execute(): Int {
        val credentialOutput = System.out
        // BOSS and supabase-kt emit startup diagnostics to stdout. Codex's
        // command-backed auth contract requires stdout to contain only the
        // bearer token, so route all helper-mode diagnostics to stderr.
        System.setOut(System.err)
        return try {
            SingleInstanceManager.requestLlmToken().fold(
                onSuccess = { token ->
                    credentialOutput.print(token)
                    0
                },
                onFailure = { error ->
                    System.err.println(
                        error.message ?: "Could not obtain a RISA LLM token. Open BOSS, sign in, and retry.",
                    )
                    1
                },
            )
        } finally {
            System.setOut(credentialOutput)
        }
    }

    /**
     * The RISA GLM credential, from the shared broker registry.
     *
     * A thin adapter now: the exchange, the session handling and the endpoint all live in
     * [CredentialBrokerClient], so this path and the plugin-facing
     * `BrokeredCredentialProvider` cannot drift apart or hold two copies of the URL. Called
     * in the **running** BOSS process, over the single-instance channel, never in the helper.
     */
    internal suspend fun fetchTokenForRunningBoss(): String =
        CredentialBrokerClient
            .exchange(CredentialBrokers.RISA_GLM)
            .getOrElse { error -> error(error.message ?: "Could not obtain a RISA LLM token.") }
            .token

    /**
     * Kept as the tested entry point for the broker's error shape, delegating so there is
     * one parser rather than two that can disagree about what a gateway error looks like.
     */
    internal fun parseGatewayError(body: String): String = CredentialBrokerClient.parseBrokerError(body)
}
