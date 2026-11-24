package ai.rever.boss.services.auth

import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the two decisions that turned one transient mail failure into a dead end on 2026-08-24,
 * when Supabase's SMTP hop drew a `451 4.3.0 Mail server temporarily rejected message` from Gmail:
 * whether the send is worth another try, and what the person staring at the sign-in form is told.
 */
class EmailAuthServiceFailureTest {
    @Test
    fun `a failed mail send is worth another attempt`() {
        assertTrue(EmailAuthService.isTransientSendFailure(mailSendFailure()))
    }

    @Test
    fun `a rate limit is not worth another attempt`() {
        // Asking again would spend another slice of the sender's hourly email budget and push the
        // address further from being able to sign in at all.
        val rateLimited =
            authFailure(HttpStatusCode.TooManyRequests, "over_email_send_rate_limit", "Email rate limit exceeded")
        assertFalse(EmailAuthService.isTransientSendFailure(rateLimited))
    }

    @Test
    fun `a validation failure is not worth another attempt`() {
        val invalid =
            authFailure(HttpStatusCode.BadRequest, "email_address_invalid", "Unable to validate email address")
        assertFalse(EmailAuthService.isTransientSendFailure(invalid))
    }

    @Test
    fun `a failed mail send reads as a sentence, not as supabase-kt's request dump`() {
        val failure = mailSendFailure()

        // Precondition, so this test cannot quietly pass if supabase-kt stops building its message
        // this way: `exception.message` really is a multi-line dump of the request we sent, and it
        // is what the login form used to render verbatim.
        val raw = requireNotNull(failure.message)
        assertTrue(raw.contains("Headers:"), "supabase-kt no longer dumps request headers into message")
        assertTrue(raw.contains("URL:"), "supabase-kt no longer dumps the request URL into message")

        // We deliberately keep logging this exception in full. That is only safe while supabase-kt
        // truncates header values; if it ever stops, the log picks up a live bearer token.
        assertFalse(raw.contains(TEST_BEARER_TOKEN), "supabase-kt now echoes the bearer token into the message we log")

        val shown = EmailAuthService.describeSendFailure(failure)

        assertEquals("We couldn't send the email just now. Please try again in a minute.", shown)
        for (fragment in listOf("URL:", "Headers:", "Http Method:", "Authorization", "unexpected_failure")) {
            assertFalse(shown.contains(fragment), "sign-in screen still shows `$fragment`")
        }
    }

    @Test
    fun `a rate limit tells the user to wait rather than to retry`() {
        val rateLimited =
            authFailure(HttpStatusCode.TooManyRequests, "over_email_send_rate_limit", "Email rate limit exceeded")
        assertEquals(
            "Too many sign-in emails have been sent recently. Please try again in an hour.",
            EmailAuthService.describeSendFailure(rateLimited),
        )
    }

    @Test
    fun `an unfamiliar failure keeps the server's own description and drops the dump`() {
        val unknown =
            authFailure(HttpStatusCode.BadRequest, "some_code_added_later", "Signups are limited to invited addresses")

        val shown = EmailAuthService.describeSendFailure(unknown)

        assertEquals("Signups are limited to invited addresses", shown)
        assertFalse(shown.contains("Headers:"), "sign-in screen still shows the request dump")
    }
}
