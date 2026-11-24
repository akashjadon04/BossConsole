package ai.rever.boss.services.auth

import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogSanitizer
import io.github.jan.supabase.auth.OtpType
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.providers.builtin.OTP
import io.github.jan.supabase.exceptions.RestException
import kotlinx.coroutines.delay
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Handles email-based authentication operations
 */
internal object EmailAuthService {
    private val logger = BossLogger.forComponent("EmailAuthService")

    /**
     * How long to wait between magic-link send attempts, one entry per retry - so one retry, two
     * attempts in all.
     *
     * The wait is at least 2s on purpose: it clears Supabase's `smtp_max_frequency` (1s), which
     * keeps a retry from turning a transient failure into a rate-limit failure.
     *
     * Deliberately short. Every attempt asks Supabase to send another email, so the ceiling has to
     * stay under whatever `rate_limit_email_sent` is set to for the project - a number that lives
     * in the Supabase dashboard, not in this repo. It reads 2/hour today and is not enforced at
     * that value (a 29-day log sweep found no `over_email_send_rate_limit` at all, and one hour on
     * 2026-08-24 carried 7 sends), but the ceiling stays low so this code is correct either way.
     */
    private val SEND_RETRY_BACKOFF = listOf(2.seconds)

    /**
     * Stop retrying once this much has elapsed, measured from the first attempt.
     *
     * The failure worth retrying is fast - Supabase answered the 2026-08-24 one in 6.4s - whereas
     * a send that burns the full 30s request timeout (`SupabaseConfig`) has already spent longer
     * than anyone will wait, and a second attempt would only double a spinner that is already too
     * long. Retrying only the fast failures keeps the worst case near the single-request timeout
     * it had before this loop existed.
     */
    private val SEND_RETRY_BUDGET = 15.seconds

    private val SERVER_ERRORS = 500..599

    private const val TRANSIENT_SEND_MESSAGE = "We couldn't send the email just now. Please try again in a minute."

    /**
     * Mark email as verified - called when deep link indicates successful verification
     */
    suspend fun verifyEmail(
        token: String,
        type: String = "magiclink",
    ): Result<Unit> =
        try {
            logger.info(LogCategory.AUTH, "Email verification confirmed via deep link", mapOf("type" to type))

            // Use our magic link verification method with the correct type
            verifyMagicLinkToken(token, type = type).fold(
                onSuccess = {
                    logger.info(LogCategory.AUTH, "Magic link verification successful")
                    Result.success(Unit)
                },
                onFailure = { error ->
                    logger.warn(LogCategory.AUTH, "Magic link verification failed", error = error)
                    Result.failure(error)
                },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error(LogCategory.AUTH, "Email verification processing failed", error = e)
            Result.failure(Exception("Failed to process email verification: ${e.message}"))
        }

    /**
     * Send magic link to user's email for passwordless authentication
     * This works for both new signups and existing users (including unconfirmed ones)
     */
    suspend fun sendMagicLink(email: String): Result<Unit> = sendMagicLink(email, send = ::requestMagicLink)

    /**
     * The retry loop, with the send and the clock passed in so it can be driven in a test without a
     * live Supabase client - and so [SEND_RETRY_BUDGET], which reads wall-clock time, is reachable
     * from one. Production calls go through [sendMagicLink].
     */
    internal suspend fun sendMagicLink(
        email: String,
        timeSource: TimeSource = TimeSource.Monotonic,
        send: suspend (String) -> Unit,
    ): Result<Unit> {
        logger.info(LogCategory.AUTH, "Sending magic link", mapOf("email" to LogSanitizer.maskEmail(email)))

        val startedAt = timeSource.markNow()
        var retries = 0
        while (true) {
            try {
                send(email)
                logger.info(LogCategory.AUTH, "Magic link sent successfully", mapOf("attempt" to retries + 1))
                return Result.success(Unit)
            } catch (e: CancellationException) {
                // Cancellation is the caller walking away, not a send failure. Rethrowing is what
                // structured concurrency requires; note that CoreLoginViewModel's scope is never
                // cancelled today, so nothing actually interrupts this loop yet.
                throw e
            } catch (e: Exception) {
                val elapsed = startedAt.elapsedNow()
                val backoff =
                    SEND_RETRY_BACKOFF
                        .getOrNull(retries)
                        ?.takeIf { isTransientSendFailure(e) && elapsed < SEND_RETRY_BUDGET }
                logger.warn(
                    LogCategory.AUTH,
                    "Magic link sending failed",
                    mapOf(
                        "exceptionType" to (e::class.simpleName ?: "unknown"),
                        "attempt" to retries + 1,
                        "elapsedMs" to elapsed.inWholeMilliseconds,
                        "willRetry" to (backoff != null),
                    ),
                    error = e,
                )
                if (backoff == null) return Result.failure(Exception(describeSendFailure(e)))
                delay(backoff)
                retries++
            }
        }
    }

    /** The real send. signInWith(OTP) handles multiple cases:
     * 1. New user - creates unconfirmed user and sends signup link
     * 2. Existing confirmed user - sends login link
     * 3. Existing unconfirmed user - resends signup/confirmation link
     */
    private suspend fun requestMagicLink(email: String) {
        logger.debug(LogCategory.AUTH, "Using Supabase endpoint", mapOf("url" to SupabaseConfig.client.supabaseUrl))
        SupabaseConfig.client.auth.signInWith(OTP) {
            this.email = email
            // The createUser flag is true by default, which means:
            // - If user doesn't exist, create them (signup)
            // - If user exists (confirmed or not), just send the link
        }
    }

    /**
     * Whether [error] is worth another attempt.
     *
     * Supabase hands the message to SMTP inside the `/otp` request and does not retry that hop, so
     * a mail-provider hiccup comes back as a 500. On 2026-08-24 Gmail answered
     * `451 4.3.0 Mail server temporarily rejected message` and the sign-in dead-ended, while the
     * identical request six minutes later went through untouched. Anything answered with a 4xx -
     * rate limits, validation, disabled signups - is a settled answer, and asking again would only
     * spend more of the sender's hourly email budget.
     *
     * [SessionRecoveryPolicy.actionFor] answers the same question for a session refresh and answers
     * it differently on purpose, so the two are worth reading together. It retries a bare transport
     * failure; this does not. A refresh is idempotent, so re-asking after a dropped connection
     * costs nothing, whereas a send that died mid-flight may already have handed the message to
     * SMTP - retrying it risks a second email, or a second charge against the send budget, to fix a
     * failure that may not have happened. The practical cost is that a connection reset or an
     * `HttpRequestTimeoutException` is not a [RestException] and so is *not* retried here.
     */
    internal fun isTransientSendFailure(error: Throwable) = error is RestException && error.statusCode in SERVER_ERRORS

    /**
     * One sentence about a failed send, for the sign-in screen.
     *
     * supabase-kt builds [RestException.message] out of the error code, the request URL and the
     * whole request header list, and that dump used to be rendered verbatim under the email field.
     * It still reaches the log with the exception attached; the screen gets prose.
     */
    internal fun describeSendFailure(error: Throwable): String {
        if (isTransientSendFailure(error)) return TRANSIENT_SEND_MESSAGE
        return when ((error as? AuthRestException)?.errorCode) {
            AuthErrorCode.UserNotFound -> {
                "No account found with this email address"
            }

            AuthErrorCode.EmailAddressInvalid -> {
                "That email address doesn't look valid. Please check it and try again."
            }

            AuthErrorCode.EmailAddressNotAuthorized -> {
                "This email address isn't allowed to sign in."
            }

            AuthErrorCode.UserBanned -> {
                "This account has been suspended. Please contact support."
            }

            AuthErrorCode.SignupDisabled, AuthErrorCode.EmailProviderDisabled, AuthErrorCode.OtpDisabled -> {
                "Email sign-in is currently unavailable. Please try again later."
            }

            // Split on purpose: `rate_limit_email_sent` is an hourly budget, so "a few minutes" is
            // wrong advice - and MagicLinkWaitingScreen's resend cooldown tops out at 600s, which
            // would invite a retry that fails again.
            AuthErrorCode.OverEmailSendRateLimit -> {
                "Too many sign-in emails have been sent recently. Please try again in an hour."
            }

            AuthErrorCode.OverRequestRateLimit -> {
                "Too many attempts. Please wait a few minutes before trying again."
            }

            else -> {
                describeUncodedFailure(error, fallback = "Failed to send magic link")
            }
        }
    }

    /**
     * Wording for failures that carry no [AuthErrorCode] we recognise - an older server build, a
     * transport error, or a code supabase-kt has not learned yet. Falls back to the server's own
     * description, which reads fine on its own ("Error sending magic link email"); it is only the
     * URL and header block bolted onto `message` that does not belong on screen.
     */
    private fun describeUncodedFailure(
        error: Throwable,
        fallback: String,
    ): String =
        when {
            error.message?.contains("cancelled") == true -> {
                "Network request cancelled. Please check your internet connection."
            }

            error.message?.contains("User not found") == true -> {
                "No account found with this email address"
            }

            error.message?.contains("Email rate limit exceeded") == true -> {
                "Too many attempts. Please wait a few minutes before trying again."
            }

            else -> {
                (error as? AuthRestException)?.errorDescription?.takeIf { it.isNotBlank() } ?: fallback
            }
        }

    /**
     * Verify magic link token using SDK's verifyEmailOtp method
     * For magic links, we need to use token_hash verification
     */
    suspend fun verifyMagicLinkToken(
        token: String,
        email: String? = null,
        type: String = "magiclink",
    ): Result<Boolean> =
        try {
            logger.debug(
                LogCategory.AUTH,
                "Starting magic link verification",
                mapOf(
                    "type" to type,
                    "hasEmail" to (email != null),
                    "tokenLength" to token.length,
                ),
            )

            // Try using the SDK's verifyEmailOtp with tokenHash
            // Magic links use token_hash verification
            val otpType =
                when (type) {
                    "signup" -> OtpType.Email.SIGNUP
                    "magiclink" -> OtpType.Email.MAGIC_LINK
                    "recovery" -> OtpType.Email.RECOVERY
                    "invite" -> OtpType.Email.INVITE
                    else -> OtpType.Email.EMAIL
                }

            logger.debug(LogCategory.AUTH, "Mapped OTP type", mapOf("otpType" to otpType.toString()))

            // The SDK should handle the session properly
            // For magic links, we need the email address
            if (email != null) {
                logger.debug(LogCategory.AUTH, "Verifying with email", mapOf("email" to LogSanitizer.maskEmail(email)))
                // Use the version with email and token
                SupabaseConfig.client.auth.verifyEmailOtp(
                    type = otpType,
                    email = email,
                    token = token,
                )
            } else {
                logger.debug(LogCategory.AUTH, "Verifying with tokenHash (no email)")
                // Fallback to tokenHash version if no email provided
                SupabaseConfig.client.auth.verifyEmailOtp(
                    type = otpType,
                    tokenHash = token,
                )
            }

            logger.info(LogCategory.AUTH, "SDK verifyEmailOtp completed successfully")

            // Check if we have a session now
            val currentSession = SupabaseConfig.client.auth.currentSessionOrNull()
            val hasSession = currentSession != null
            logger.debug(
                LogCategory.AUTH,
                "Session state after verification",
                mapOf(
                    "hasSession" to hasSession,
                    "userEmail" to (currentSession?.user?.email?.let { LogSanitizer.maskEmail(it) } ?: "none"),
                ),
            )

            // Mark that user authenticated via magic link
            AuthStateManager.setAuthenticatedViaMagicLink(true)
            logger.info(LogCategory.AUTH, "Magic link verification complete")

            Result.success(true)
        } catch (e: CancellationException) {
            // Same reason as the send path: cancellation is not a verification failure.
            throw e
        } catch (e: Exception) {
            logger.warn(
                LogCategory.AUTH,
                "Magic link verification failed",
                mapOf(
                    "exceptionType" to (e::class.simpleName ?: "unknown"),
                ),
                error = e,
            )

            val errorMessage =
                when {
                    e.message?.contains("Invalid token") == true -> {
                        "This magic link has expired. Magic links are valid for 15 minutes. Please request a new one."
                    }

                    e.message?.contains("already_used") == true -> {
                        "This magic link has already been used. Please request a new one if you need to sign in again."
                    }

                    e.message?.contains("expired") == true -> {
                        "This magic link has expired. Magic links are valid for 15 minutes. Please request a new one."
                    }

                    e.message?.contains("JsonLiteral") == true ||
                        e.message?.contains("JsonObject") == true -> {
                        "Server response format issue - please try again"
                    }

                    e.message?.contains("404") == true -> {
                        "Magic link verification endpoint not found"
                    }

                    e.message?.contains("cancelled") == true -> {
                        "Network request cancelled. Please check your internet connection."
                    }

                    else -> {
                        describeUncodedFailure(e, fallback = "Magic link verification failed")
                    }
                }

            Result.failure(Exception(errorMessage))
        }
}
