package ai.rever.boss.services.auth

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

/**
 * Drives the send loop itself. `runTest` skips the backoff in virtual time and [TestTimeSource]
 * stands in for the wall clock the budget reads, so a test can reach both without waiting.
 */
@OptIn(ExperimentalCoroutinesApi::class) // testScheduler.currentTime, to assert the backoff actually waits
class EmailAuthServiceRetryTest {
    @Test
    fun `a transient failure is retried and the second attempt is allowed to succeed`() =
        runTest {
            var attempts = 0
            val result =
                EmailAuthService.sendMagicLink("someone@example.com", TestTimeSource()) {
                    attempts++
                    if (attempts == 1) throw mailSendFailure()
                }

            assertTrue(result.isSuccess)
            assertEquals(2, attempts)
        }

    @Test
    fun `a transient failure that keeps failing gives up after one retry`() =
        runTest {
            var attempts = 0
            val result =
                EmailAuthService.sendMagicLink("someone@example.com", TestTimeSource()) {
                    attempts++
                    throw mailSendFailure()
                }

            // Two attempts, not three: every attempt asks Supabase to send another email.
            assertEquals(2, attempts)
            assertEquals(
                "We couldn't send the email just now. Please try again in a minute.",
                result.exceptionOrNull()?.message,
            )
        }

    @Test
    fun `the retry waits out smtp_max_frequency before asking again`() =
        runTest {
            val startedAt = testScheduler.currentTime
            var attempts = 0
            EmailAuthService.sendMagicLink("someone@example.com", TestTimeSource()) {
                attempts++
                if (attempts == 1) throw mailSendFailure()
            }

            // Supabase rejects a second send inside smtp_max_frequency (1s) with a rate-limit
            // error, so a retry that did not wait would replace one failure with a worse one.
            val waited = testScheduler.currentTime - startedAt
            assertTrue(waited >= 2_000, "retried after only ${waited}ms")
        }

    @Test
    fun `a rate limit is never retried`() =
        runTest {
            var attempts = 0
            val result =
                EmailAuthService.sendMagicLink("someone@example.com", TestTimeSource()) {
                    attempts++
                    throw rateLimited()
                }

            assertEquals(1, attempts)
            assertEquals(
                "Too many sign-in emails have been sent recently. Please try again in an hour.",
                result.exceptionOrNull()?.message,
            )
        }

    @Test
    fun `a slow failure is not retried, so the wait cannot double`() =
        runTest {
            val clock = TestTimeSource()
            var attempts = 0
            val result =
                EmailAuthService.sendMagicLink("someone@example.com", clock) {
                    attempts++
                    // A send that burns the 30s request timeout has already outlasted the budget.
                    clock += 30.seconds
                    throw mailSendFailure()
                }

            assertEquals(1, attempts)
            assertTrue(result.isFailure)
        }

    @Test
    fun `cancellation propagates instead of being retried`() =
        runTest {
            var attempts = 0
            assertFailsWith<kotlin.coroutines.cancellation.CancellationException> {
                EmailAuthService.sendMagicLink("someone@example.com", TestTimeSource()) {
                    attempts++
                    throw kotlin.coroutines.cancellation.CancellationException("screen closed")
                }
            }

            assertEquals(1, attempts)
        }
}
