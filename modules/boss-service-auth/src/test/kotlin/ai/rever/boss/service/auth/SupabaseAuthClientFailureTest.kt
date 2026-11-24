package ai.rever.boss.service.auth

import io.github.jan.supabase.auth.exception.AuthRestException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * The out-of-process twin of composeApp's `EmailAuthServiceFailureTest` / `EmailAuthServiceRetryTest`.
 *
 * The retry policy and the message mapping exist twice, character for character, because
 * `composeApp` cannot depend on this module - `modules/` is excluded from the build on
 * Windows-ARM64 (settings.gradle.kts), so a shared home is not available. The duplication is
 * therefore forced, but going untested was not: this module had no `src/test` at all, so the copy
 * that ships to the microkernel was the copy nothing checked.
 *
 * KEEP IN LOCKSTEP with the composeApp tests. Both files assert the same literal strings on
 * purpose; if you change wording in one, the other's expectations are the checklist.
 */
class SupabaseAuthClientFailureTest {
    private val client = SupabaseAuthClient(supabaseUrl = PROJECT_URL, supabaseAnonKey = "test-anon-key")

    @Test
    fun `a failed mail send is worth another attempt`() {
        assertTrue(client.isTransientSendFailure(mailSendFailure()))
    }

    @Test
    fun `a rate limit is not worth another attempt`() {
        assertTrue(!client.isTransientSendFailure(rateLimited()))
    }

    @Test
    fun `a failed mail send reads as a sentence, not as supabase-kt's request dump`() {
        val failure = mailSendFailure()
        val raw = requireNotNull(failure.message)
        assertTrue(raw.contains("Headers:"), "supabase-kt no longer dumps request headers into message")
        assertTrue(!raw.contains(BEARER), "supabase-kt now echoes the bearer token into the message we log")

        val shown = client.describeSendFailure(failure)

        assertEquals("We couldn't send the email just now. Please try again in a minute.", shown)
        for (fragment in listOf("URL:", "Headers:", "Http Method:", "Authorization", "unexpected_failure")) {
            assertTrue(!shown.contains(fragment), "sign-in screen still shows `$fragment`")
        }
    }

    @Test
    fun `an hourly email budget is not described as a few minutes`() {
        assertEquals(
            "Too many sign-in emails have been sent recently. Please try again in an hour.",
            client.describeSendFailure(rateLimited()),
        )
    }

    @Test
    fun `a password sign-in failure does not ship the request dump either`() {
        val shown =
            client.describeAuthFailure(
                authFailure(HttpStatusCode.BadRequest, "invalid_credentials", "Invalid login credentials"),
                transient = "unused",
                fallback = "Sign-in failed",
            )

        assertEquals("That email and password don't match.", shown)
    }

    @Test
    fun `a transient failure is retried once`() =
        runTest {
            var attempts = 0
            val result =
                client.sendMagicLink("someone@example.com", TestTimeSource()) {
                    attempts++
                    if (attempts == 1) throw mailSendFailure()
                }

            assertEquals(2, attempts)
            assertTrue(result is AuthResult.MagicLinkSent)
        }

    @Test
    fun `a slow failure is not retried`() =
        runTest {
            val clock = TestTimeSource()
            var attempts = 0
            client.sendMagicLink("someone@example.com", clock) {
                attempts++
                clock += 30.seconds
                throw mailSendFailure()
            }

            assertEquals(1, attempts)
        }

    private fun mailSendFailure() = authFailure(HttpStatusCode.InternalServerError, "unexpected_failure", MAIL_FAILED)

    private fun rateLimited() = authFailure(HttpStatusCode.TooManyRequests, "over_email_send_rate_limit", RATE_LIMITED)

    private fun authFailure(
        status: HttpStatusCode,
        code: String,
        description: String,
    ) = AuthRestException(code, description, responseWith(status))

    private fun responseWith(status: HttpStatusCode) =
        runBlocking {
            HttpClient(MockEngine { respond(content = "", status = status) }).use { http ->
                http.post("$PROJECT_URL/auth/v1/otp") {
                    header(HttpHeaders.Authorization, "Bearer $BEARER")
                }
            }
        }

    private companion object {
        const val BEARER = "test-token-must-never-be-logged"
        const val PROJECT_URL = "https://project.supabase.co"
        const val MAIL_FAILED = "Error sending magic link email"
        const val RATE_LIMITED = "Email rate limit exceeded"
    }
}
