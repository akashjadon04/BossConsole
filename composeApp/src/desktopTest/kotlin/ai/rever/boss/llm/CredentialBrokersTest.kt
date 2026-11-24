package ai.rever.boss.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The broker registry is the security boundary for this feature: a plugin names a broker by
 * id, and the host decides what that means. These pin the properties that make that true.
 */
class CredentialBrokersTest {
    @Test
    fun `a plugin can only reach a broker this build declares`() {
        // The whole point of id-not-URL. An unknown id is a refusal, not an attempt.
        assertNull(CredentialBrokers.find("not-a-broker"))
        assertNull(CredentialBrokers.find("https://attacker.example/collect"))
        assertNotNull(CredentialBrokers.find(CredentialBrokers.RISA_GLM))
    }

    @Test
    fun `an unknown broker id fails without touching the network`() {
        val result = kotlinx.coroutines.runBlocking { CredentialBrokerClient.exchange("nope") }

        assertTrue(result.isFailure)
        assertTrue(
            result
                .exceptionOrNull()
                ?.message
                .orEmpty()
                .contains("does not know"),
        )
    }

    @Test
    fun `every broker declares an https endpoint and what it is scoped to`() {
        // scopedTo is published to plugins so a careful one can check where it is about to
        // post a bearer token. A broker without it gives them nothing to check against.
        CredentialBrokers.all().forEach { broker ->
            assertTrue(broker.tokenUrl.startsWith("https://"), "${broker.id} token URL is not https")
            val scope = assertNotNull(broker.scopedTo, "${broker.id} declares no scope")
            assertTrue(scope.startsWith("https://"), "${broker.id} scope is not https")
        }
    }

    @Test
    fun `broker ids are unique`() {
        // find() takes the first match, so a duplicate would silently shadow one.
        val ids = CredentialBrokers.all().map { it.id }

        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `a broker error message never carries the raw body`() {
        val leaky = "sk-live-secret and api_base=https://internal.gateway.example/v1"

        val message = CredentialBrokerClient.parseBrokerError(leaky)

        assertFalse(message.contains("sk-live-secret"), message)
        assertFalse(message.contains("internal.gateway.example"), message)
    }

    @Test
    fun `the RISA endpoint is overridable for a staging build but defaults to production`() {
        // The override exists so a dev build can point at staging. It comes from the
        // environment, which is the host's, not from anything a plugin supplies.
        val broker = assertNotNull(CredentialBrokers.find(CredentialBrokers.RISA_GLM))
        val fromEnv = System.getenv("RISA_LLM_TOKEN_URL")

        if (fromEnv.isNullOrBlank()) {
            assertEquals("https://llm.risa.inc/auth/token", broker.tokenUrl)
        } else {
            assertEquals(fromEnv, broker.tokenUrl)
        }
    }
}
