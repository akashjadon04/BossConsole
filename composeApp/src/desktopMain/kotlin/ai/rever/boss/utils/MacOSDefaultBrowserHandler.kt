package ai.rever.boss.utils

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.mac.LaunchServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempFile

private val logger = BossLogger.forComponent("MacOSDefaultBrowserHandler")

/**
 * macOS-specific handler for default browser functionality
 *
 * Uses Launch Services to:
 * - Check who currently handles http/https
 * - Set BOSS as the default browser
 *
 * **Primary path is [LaunchServices]**, bound through JNA. The Swift scripts
 * below remain as a fallback for the case where that binding fails, and only for
 * URL schemes - a machine that cannot load CoreServices through JNA is odd
 * enough that keeping a second implementation of every call would cost more than
 * it buys. See [LaunchServices] for why the shell-out is no longer the primary
 * path (it needs Xcode installed, which most users do not have).
 */
object MacOSDefaultBrowserHandler {
    private const val PROCESS_TIMEOUT_SECONDS = 30L

    /** The schemes "default browser" means. Both must point at BOSS for it to be true. */
    private val BROWSER_SCHEMES = listOf("http", "https")

    /**
     * The document type that travels with the browser role.
     *
     * Included in the check and the repair because the engine bundle claimed it
     * too, and a machine where http/https point at BOSS while double-clicking an
     * `.html` file still launches a bare Chromium is not fixed.
     */
    private const val WEB_PAGE_CONTENT_TYPE = "public.html"

    /**
     * Who owns each browser scheme right now.
     *
     * Returned per scheme rather than reduced to one answer: http and https can
     * genuinely disagree (Launch Services stores them separately, and setting
     * one can succeed while the other fails), and the Settings card should not
     * flatten that into a bare false.
     */
    internal suspend fun browserHandlerStates(): Result<Map<String, DefaultHandlerState>> =
        withContext(Dispatchers.IO) {
            try {
                val handlers = getDefaultHandlers()
                val states = BROWSER_SCHEMES.associateWith { DefaultHandlerState.of(handlers[it]) }

                logger.debug(
                    LogCategory.BROWSER,
                    "macOS default browser check",
                    states.mapValues { (_, state) -> state.toString() },
                )

                Result.success(states)
            } catch (e: Exception) {
                logger.warn(LogCategory.BROWSER, "Error checking default browser on macOS", error = e)
                Result.failure(e)
            }
        }

    /**
     * The single state for the browser role: [DefaultHandlerState.Ours] only
     * when every scheme and the web-page content type point at BOSS.
     *
     * When they disagree, the *worst* answer wins, and [DefaultHandlerState.OurEngine]
     * outranks [DefaultHandlerState.Other] - because "a BOSS component stole
     * this" is the actionable thing to say, and it stays true and repairable
     * whether one scheme or all three are affected.
     */
    internal suspend fun browserHandlerState(): Result<DefaultHandlerState> =
        withContext(Dispatchers.IO) {
            // Inside the IO block, not in a `Result.map` on the result of
            // browserHandlerStates(). `map` is inline and runs on the CALLER's
            // dispatcher after that withContext has already returned, so the
            // content-type read - a native Launch Services call - was landing on
            // Dispatchers.Main via DefaultBrowserSection's LaunchedEffect. Exactly
            // what DefaultAppsManager's KDoc and docs/THREADING.md forbid.
            browserHandlerStates().map { schemeStates ->
                val contentTypeState = DefaultHandlerState.of(defaultHandlerForContentType(WEB_PAGE_CONTENT_TYPE))
                DefaultHandlerState.reduce(schemeStates.values + contentTypeState)
            }
        }

    /**
     * Check if BOSS is currently the default browser on macOS
     */
    suspend fun isDefaultBrowser(): Result<Boolean> = browserHandlerState().map { it.isOurs }

    /**
     * Set BOSS as the default browser on macOS
     *
     * Claims http, https and `public.html`. Returns true when every one of them
     * was accepted; false means the user has to finish the job in System
     * Settings, which is opened for them.
     */
    suspend fun setAsDefaultBrowser(): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                // First check if already default
                val state = browserHandlerState()
                if (state.getOrNull()?.isOurs == true) {
                    logger.info(LogCategory.BROWSER, "BOSS is already the default browser")
                    return@withContext Result.success(true)
                }

                // Sequenced with `fold`, not `all`, so a failure on http does not
                // skip https: partial success is a real state here (the OS accepts
                // them independently) and leaving one behind is worse than trying
                // both and reporting the truth.
                val schemesSet =
                    BROWSER_SCHEMES.fold(true) { acc, scheme ->
                        setDefaultHandlerForScheme(scheme) && acc
                    }
                val contentTypeSet = setDefaultHandlerForContentType(WEB_PAGE_CONTENT_TYPE)

                if (schemesSet && contentTypeSet) {
                    logger.info(LogCategory.BROWSER, "Successfully set BOSS as default browser on macOS")
                    Result.success(true)
                } else {
                    logger.warn(LogCategory.BROWSER, "Could not set default programmatically, opening System Preferences")
                    openSystemPreferences()
                    Result.success(false)
                }
            } catch (e: Exception) {
                logger.error(LogCategory.BROWSER, "Error setting default browser on macOS", error = e)
                Result.failure(e)
            }
        }

    /** Bundle id registered for the UTI [contentType], or null. Native path only. */
    internal fun defaultHandlerForContentType(contentType: String): String? =
        if (LaunchServices.isAvailable()) {
            LaunchServices.defaultHandlerForContentType(contentType)
        } else {
            // No Swift fallback for content types on purpose: it would mean a
            // second full implementation of the file-type feature for a case
            // that only arises when CoreServices cannot be bound at all. Null
            // reads as "unknown" and the UI says so.
            logger.debug(
                LogCategory.BROWSER,
                "Launch Services unavailable, content type owner unknown",
                mapOf("type" to contentType),
            )
            null
        }

    /** Registers BOSS for the UTI [contentType]. Native path only. */
    internal fun setDefaultHandlerForContentType(contentType: String): Boolean =
        LaunchServices.isAvailable() &&
            LaunchServices.setDefaultHandlerForContentType(contentType, BOSS_MACOS_BUNDLE_ID)

    /**
     * Get default handlers for every browser scheme.
     *
     * Native when possible; one Swift process for all schemes otherwise, which
     * is why the fallback is written as a batch rather than per scheme.
     */
    private fun getDefaultHandlers(): Map<String, String> {
        if (LaunchServices.isAvailable()) {
            return BROWSER_SCHEMES
                .mapNotNull { scheme ->
                    LaunchServices.defaultHandlerForScheme(scheme)?.let { scheme to it }
                }.toMap()
        }
        return getDefaultHandlersViaSwift()
    }

    private fun setDefaultHandlerForScheme(scheme: String): Boolean =
        if (LaunchServices.isAvailable()) {
            LaunchServices.setDefaultHandlerForScheme(scheme, BOSS_MACOS_BUNDLE_ID)
        } else {
            setDefaultHandlerForSchemeViaSwift(scheme)
        }

    /**
     * Fallback: query both handlers in a single Swift invocation.
     *
     * One process for all schemes, because each one pays a Swift front-end
     * compile. Requires Xcode or the Command Line Tools; when `swift` is absent
     * this returns an empty map and the caller reports "unknown" rather than
     * "not default".
     */
    private fun getDefaultHandlersViaSwift(): Map<String, String> {
        val scriptFile = createTempFile("get_default_browser", ".swift").toFile()
        try {
            val schemeList = BROWSER_SCHEMES.joinToString(", ") { "\"$it\"" }
            val swiftScript =
                """
                import Foundation
                import ApplicationServices

                for scheme in [$schemeList] {
                    if let handler = LSCopyDefaultHandlerForURLScheme(scheme as CFString) {
                        print("\(scheme)=\(handler.takeRetainedValue() as String)")
                    }
                }
                """.trimIndent()

            scriptFile.writeText(swiftScript)

            val process =
                ProcessBuilder("swift", scriptFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()

            // Read output on background thread so waitFor timeout can fire if Swift hangs
            val outputFuture =
                CompletableFuture.supplyAsync {
                    BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
                }

            val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn(LogCategory.BROWSER, "Swift process timed out getting default handlers")
                return emptyMap()
            }

            if (process.exitValue() != 0) return emptyMap()

            val output = outputFuture.get(1, TimeUnit.SECONDS)

            // Parse "http=com.apple.Safari\nhttps=com.apple.Safari" format
            return output
                .lines()
                .mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) parts[0] to parts[1] else null
                }.toMap()
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error getting default handlers", error = e)
            return emptyMap()
        } finally {
            scriptFile.delete()
        }
    }

    /** Fallback: set the default handler for a URL scheme using a Swift script. */
    private fun setDefaultHandlerForSchemeViaSwift(scheme: String): Boolean {
        val scriptFile = createTempFile("set_default_browser", ".swift").toFile()
        try {
            val swiftScript =
                """
                import AppKit
                import ApplicationServices

                let bundleId = "$BOSS_MACOS_BUNDLE_ID"
                let scheme = "$scheme"

                let status = LSSetDefaultHandlerForURLScheme(scheme as CFString, bundleId as CFString)

                if status == noErr {
                    print("✅ Set default handler for \(scheme)")
                    exit(0)
                } else {
                    print("❌ Failed to set default handler for \(scheme): \(status)")
                    exit(1)
                }
                """.trimIndent()

            scriptFile.writeText(swiftScript)

            val process =
                ProcessBuilder("swift", scriptFile.absolutePath)
                    .redirectErrorStream(true)
                    .start()

            // Read output on background thread so waitFor timeout can fire if Swift hangs
            val outputFuture =
                CompletableFuture.supplyAsync {
                    BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
                }

            val finished = process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                logger.warn(LogCategory.BROWSER, "Swift process timed out setting default handler", mapOf("scheme" to scheme))
                return false
            }

            val output = outputFuture.get(1, TimeUnit.SECONDS)
            logger.debug(LogCategory.BROWSER, "Swift script output", mapOf("scheme" to scheme, "output" to output))

            return process.exitValue() == 0
        } catch (e: Exception) {
            logger.warn(
                LogCategory.BROWSER,
                "Error setting default handler",
                mapOf("scheme" to scheme, "error" to (e.message ?: "unknown")),
            )
            return false
        } finally {
            scriptFile.delete()
        }
    }

    /**
     * Open System Settings (Ventura+) or System Preferences (Monterey and earlier)
     * to the Default Browser settings pane
     */
    internal fun openSystemPreferences() {
        try {
            val url =
                if (getMacOSMajorVersion() >= 13) {
                    "x-apple.systempreferences:com.apple.Desktop-Settings.extension"
                } else {
                    "x-apple.systempreferences:com.apple.preference.general"
                }

            val process = ProcessBuilder("open", url).start()
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn(LogCategory.BROWSER, "Timed out opening System Settings")
                return
            }

            logger.info(LogCategory.BROWSER, "Opened System Settings for user to set default browser")
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error opening System Settings", error = e)
        }
    }

    /**
     * Get the macOS major version number (e.g. 13 for Ventura, 14 for Sonoma)
     */
    private fun getMacOSMajorVersion(): Int {
        return try {
            val process = ProcessBuilder("sw_vers", "-productVersion").start()
            val version = BufferedReader(InputStreamReader(process.inputStream)).use { it.readText().trim() }
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn(LogCategory.BROWSER, "Timed out getting macOS version")
                return 0
            }
            version.substringBefore(".").toIntOrNull() ?: 0
        } catch (e: Exception) {
            logger.warn(LogCategory.BROWSER, "Error getting macOS version", error = e)
            0
        }
    }
}
