package ai.rever.boss.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class RisaLlmTokenCommandTest {
    @Test
    fun extractsSafeGatewayError() {
        // A broker's own error.message is written for a person, so it is shown as-is.
        val message =
            RisaLlmTokenCommand.parseGatewayError(
                """{"error":{"message":"RISA LLM access has been disabled for this account"}}""",
            )

        assertEquals("RISA LLM access has been disabled for this account", message)
    }

    @Test
    fun doesNotEchoUnknownProviderPayload() {
        // Anything that is not that field is not written for a person, and this string is
        // shown in a panel and returned over the single-instance channel. Asserting the
        // property rather than the exact sentence: the wording became broker-generic when
        // the parser moved, and the thing that matters is that the body does not leak.
        val body = "upstream secret-like failure sk-abc123 api_base=https://internal.example"

        val message = RisaLlmTokenCommand.parseGatewayError(body)

        assertFalse(message.contains("sk-abc123"), message)
        assertFalse(message.contains("internal.example"), message)
        assertEquals("The credential broker rejected the token request.", message)
    }

    @Test
    fun aMalformedBodyStillYieldsAMessage() {
        // Not-JSON and JSON-without-an-error both have to produce something showable, or a
        // failing exchange surfaces as an empty toast.
        assertEquals(
            "The credential broker rejected the token request.",
            RisaLlmTokenCommand.parseGatewayError("{ this is not json"),
        )
        assertEquals(
            "The credential broker rejected the token request.",
            RisaLlmTokenCommand.parseGatewayError("""{"status":"nope"}"""),
        )
    }
}
