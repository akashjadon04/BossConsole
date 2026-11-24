package ai.rever.boss.services.auth

import io.github.jan.supabase.auth.exception.AuthRestException
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking

/**
 * Mints the real supabase-kt exception rather than a stand-in, because the thing under test is how
 * supabase-kt builds these: the error code, the request URL and the whole header list all end up in
 * [Throwable.message], and that is what used to reach the sign-in screen.
 *
 * The bearer token below is deliberately a recognisable value - see the logging assertion in
 * [EmailAuthServiceFailureTest].
 */
internal const val TEST_BEARER_TOKEN = "test-token-must-never-be-logged"

internal fun authFailure(
    status: HttpStatusCode,
    code: String,
    description: String,
): AuthRestException = AuthRestException(code, description, responseWith(status))

/** The 2026-08-24 shape: Supabase could not hand the message to SMTP, so it answered 500. */
internal fun mailSendFailure(): AuthRestException =
    authFailure(HttpStatusCode.InternalServerError, "unexpected_failure", "Error sending magic link email")

/** The shape that must never be retried: another attempt spends more of the hourly send budget. */
internal fun rateLimited(): AuthRestException =
    authFailure(HttpStatusCode.TooManyRequests, "over_email_send_rate_limit", "Email rate limit exceeded")

private fun responseWith(status: HttpStatusCode) =
    runBlocking {
        HttpClient(MockEngine { respond(content = "", status = status) }).use { client ->
            client.post("https://project.supabase.co/auth/v1/otp") {
                header(HttpHeaders.Authorization, "Bearer $TEST_BEARER_TOKEN")
            }
        }
    }
