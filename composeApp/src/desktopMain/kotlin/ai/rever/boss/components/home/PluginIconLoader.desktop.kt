package ai.rever.boss.components.home

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO

private val logger = BossLogger.forComponent("PluginIconLoader")

/** A cache entry, so a *resolved absence* can be stored - ConcurrentHashMap rejects null values. */
private class CachedIcon(
    val painter: Painter?,
)

/**
 * Fetches and decodes plugin store icons. Shape follows `HighQualityFaviconService`.
 *
 * **What this does not defend against.** `icon_url` is operator-supplied data and redirects are
 * followed, so a store row (or a redirect off one) can make this client issue a GET at an address
 * of the row author's choosing, including one inside the network the app runs in. The scheme check
 * only covers the initial URL. Nothing is returned to the caller but a decoded image or null, so
 * this leaks reachability rather than content, and only an admin who can write store rows can
 * reach it - but a deployment that treats store rows as untrusted should pin an allowed host or
 * proxy these through the store instead.
 */
private object PluginIcons {
    private const val REQUEST_TIMEOUT_MS = 2500L
    private const val MAX_CONCURRENT_FETCHES = 3

    /**
     * Largest icon accepted, in bytes.
     *
     * `icon_url` is an arbitrary URL from a database row, so the response size is not something
     * this process controls. Checked against `Content-Length` **before** reading the body, then
     * again on the bytes: the header is a hint a server can omit or lie about, so the second check
     * is what actually holds, and a chunked response with no length still gets read once at up to
     * whatever the timeout allows. Bounding that properly needs a streaming read with a running
     * count, which is more machinery than a 24.dp icon justifies.
     */
    private const val MAX_BYTES = 512 * 1024

    /**
     * In memory only, for the session.
     *
     * No disk cache, unlike the favicon service: there are tens of these rather than hundreds,
     * they are only read while the home screen is open, and a disk cache would need its own
     * eviction and invalidation for an icon that can change server-side at any time. A miss costs
     * one small request.
     *
     * Keyed on the URL with a **nullable** painter as the value, so a failure caches too -
     * otherwise every recomposition would retry an unreachable host. `ConcurrentHashMap` cannot
     * store a null value, hence `Optional`-style wrapping via a one-element holder rather than a
     * `Result`, which only ever held `success` and read as though failures were `failure`.
     */
    private val cache = ConcurrentHashMap<String, CachedIcon>()

    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

    private val httpClient by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
                connectTimeoutMillis = REQUEST_TIMEOUT_MS
            }
            expectSuccess = false
            followRedirects = true
        }
    }

    // Two guard clauses (blank url, cache hit) before the work, each a distinct reason to answer
    // without touching the network.
    @Suppress("ReturnCount")
    suspend fun load(iconUrl: String): Painter? {
        // The common case: `icon_url` is blank for every row in the store today, so this returns
        // before touching the network and the tile renders the plugin's initials.
        if (iconUrl.isBlank()) return null
        cache[iconUrl]?.let { return it.painter }

        val attempt = runCatching { fetch(iconUrl) }

        // Cancellation is not a fetch failure, and must not be cached as one. `fetch` suspends, so
        // runCatching also catches CancellationException - and the LaunchedEffect that calls this
        // is cancelled routinely, whenever a tile leaves composition on a filter change or a
        // reflow. Absorbing it here would write a permanent "no icon" for a perfectly reachable
        // URL, so a scroll could lose an icon for the rest of the session. It also breaks
        // structured concurrency: the cancellation has to reach the caller.
        attempt.exceptionOrNull()?.let { if (it is CancellationException) throw it }

        val painter =
            attempt
                .onFailure { error ->
                    logger.debug(
                        LogCategory.NETWORK,
                        "Plugin icon fetch failed; the tile falls back to initials",
                        mapOf("error" to error.toString()),
                    )
                }.getOrNull()

        // Cached either way, including null: a plugin whose icon 404s must not be re-requested on
        // every recomposition of the grid.
        cache[iconUrl] = CachedIcon(painter)
        return painter
    }

    private suspend fun fetch(iconUrl: String): Painter? =
        withContext(Dispatchers.IO) {
            // http(s) only. `icon_url` is operator-supplied data, and ImageIO would happily read
            // a `file:` URL, which would turn a store row into a local-file read.
            val scheme = iconUrl.substringBefore("://", missingDelimiterValue = "").lowercase()
            if (scheme != "http" && scheme != "https") {
                logger.warn(
                    LogCategory.NETWORK,
                    "Ignoring a plugin icon URL that is not http(s)",
                    mapOf("scheme" to scheme.ifEmpty { "none" }),
                )
                return@withContext null
            }

            val response = fetchSemaphore.withPermit { httpClient.get(iconUrl) }
            if (response.status != HttpStatusCode.OK) return@withContext null

            // Refuse an oversized body before reading it, where the server declares a length.
            val declaredLength = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
            if (declaredLength != null && declaredLength > MAX_BYTES) return@withContext null

            val bytes = response.readRawBytes()
            if (bytes.isEmpty() || bytes.size > MAX_BYTES) return@withContext null

            // Null for anything ImageIO cannot decode, which covers a body that is not an image.
            val image = ImageIO.read(ByteArrayInputStream(bytes)) ?: return@withContext null
            BitmapPainter(image.toComposeImageBitmap())
        }
}

actual suspend fun loadPluginIcon(iconUrl: String): Painter? = PluginIcons.load(iconUrl)
