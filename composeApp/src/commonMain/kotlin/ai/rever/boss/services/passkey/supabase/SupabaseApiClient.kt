package ai.rever.boss.services.passkey.supabase

import ai.rever.boss.services.supabase.SupabaseConfig
import ai.rever.boss.services.supabase.getSupabaseAnonKey
import ai.rever.boss.services.supabase.getSupabaseFunctionUrl
import io.github.jan.supabase.auth.auth
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json

/**
 * Handles all HTTP communication with Supabase Edge Functions for passkey operations
 *
 * New API uses RESTful endpoints:
 * - POST /passkey/auth/challenge - Generate authentication challenge
 * - POST /passkey/auth/complete - Complete authentication
 * - GET /passkey/auth/status/{sessionId} - Check authentication status
 * - POST /passkey/register/challenge - Generate registration challenge
 * - POST /passkey/register/complete - Complete registration
 * - POST /passkey/manage/list - List user passkeys
 * - POST /passkey/manage/delete - Delete a passkey
 * - POST /passkey/manage/update - Update passkey display name
 */
internal object SupabaseApiClient {
    private val httpClient = HttpClient(CIO)

    // Secure configuration - values loaded from ConfigLoader (environment variables, system properties, or local.properties)
    // Base functions URL (e.g., http://127.0.0.1:54321/functions/v1)
    private val supabaseFunctionBaseUrl: String by lazy {
        getSupabaseFunctionUrl()
    }

    // Passkey function URL (base + /passkey)
    private val passkeyFunctionUrl: String by lazy {
        "$supabaseFunctionBaseUrl/passkey"
    }

    private val supabaseAnonKey: String by lazy {
        getSupabaseAnonKey()
    }

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /**
     * Access token of the signed-in user, or null when there is no session.
     *
     * Registration endpoints require this: the server binds a new passkey to the
     * authenticated caller rather than to a userId in the request body, so a
     * request without a session is rejected with 401. Authentication endpoints
     * deliberately do not send it — they run before a session exists.
     *
     * Uses the suspending accessor, which waits for auth to finish loading and
     * refreshes an expired access token first. Reading `currentSessionOrNull()`
     * directly would hand the server a stale token whenever the access token had
     * expired but the refresh token was still good — a 401 in the middle of
     * Settings → Security, for a user who is perfectly well signed in.
     */
    suspend fun currentAccessTokenOrNull(): String? =
        runCatching { SupabaseConfig.client.auth.currentAccessTokenOrNull() }
            .getOrNull()

    // ============================================================================
    // Authentication Endpoints
    // ============================================================================

    /**
     * POST /passkey/auth/challenge - Generate authentication challenge
     */
    suspend inline fun <reified T> invokeAuthenticationChallenge(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)

        return httpClient.post("$passkeyFunctionUrl/auth/challenge") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            setBody(jsonBody)
        }
    }

    /**
     * POST /passkey/auth/complete - Complete authentication
     */
    suspend inline fun <reified T> completeAuthentication(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)

        return httpClient.post("$passkeyFunctionUrl/auth/complete") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            setBody(jsonBody)
        }
    }

    /**
     * GET /passkey/auth/status/{sessionId} - Check authentication status
     */
    suspend fun checkAuthenticationStatus(sessionId: String): HttpResponse =
        httpClient.get("$passkeyFunctionUrl/auth/status/$sessionId") {
            header("apikey", supabaseAnonKey)
        }

    // ============================================================================
    // Registration Endpoints
    // ============================================================================

    /**
     * POST /passkey/register/challenge - Generate registration challenge
     */
    suspend inline fun <reified T> invokeRegistrationChallenge(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)

        // Enrolling a passkey acts on the caller's own account, so the server
        // requires the session rather than trusting a body userId. Resolved
        // before the request builder, which is not a suspending context.
        val accessToken = currentAccessTokenOrNull()

        return httpClient.post("$passkeyFunctionUrl/register/challenge") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(jsonBody)
        }
    }

    /**
     * POST /passkey/register/complete - Complete registration
     */
    suspend inline fun <reified T> completeRegistration(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)
        val accessToken = currentAccessTokenOrNull()

        return httpClient.post("$passkeyFunctionUrl/register/complete") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(jsonBody)
        }
    }

    // ============================================================================
    // Management Endpoints
    // ============================================================================

    /**
     * POST /passkey/manage/list - List user passkeys
     */
    suspend inline fun <reified T> listPasskeys(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)
        // Management acts on the caller's own credentials; the server requires
        // the session rather than trusting a body userId
        val accessToken = currentAccessTokenOrNull()

        return httpClient.post("$passkeyFunctionUrl/manage/list") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(jsonBody)
        }
    }

    /**
     * POST /passkey/manage/delete - Delete a passkey
     */
    suspend inline fun <reified T> deletePasskey(requestData: T): HttpResponse {
        val jsonBody = json.encodeToString(requestData)
        // Management acts on the caller's own credentials; the server requires
        // the session rather than trusting a body userId
        val accessToken = currentAccessTokenOrNull()

        return httpClient.post("$passkeyFunctionUrl/manage/delete") {
            contentType(ContentType.Application.Json)
            header("apikey", supabaseAnonKey)
            accessToken?.let { header(HttpHeaders.Authorization, "Bearer $it") }
            setBody(jsonBody)
        }
    }
}
