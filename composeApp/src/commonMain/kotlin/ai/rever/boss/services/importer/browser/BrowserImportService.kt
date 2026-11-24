package ai.rever.boss.services.importer.browser

import ai.rever.boss.services.importer.ImportPreview
import ai.rever.boss.services.importer.ImportedBookmark
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext

/**
 * Reads bookmarks and saved logins straight out of an installed browser.
 *
 * Bookmarks come from files the browser leaves readable. Passwords are
 * encrypted at rest, and on macOS unsealing them prompts for keychain access —
 * so [scan] deliberately never touches them: the picker can show counts without
 * making the user approve anything, and the prompt only appears once they
 * actually choose to import.
 */
object BrowserImportService {
    private val logger = BossLogger.forComponent("BrowserImportService")

    /** Shown when a browser's passwords can't be read on this platform. */
    private const val FIREFOX_PASSWORD_NOTE =
        "Firefox passwords need its own export - use Passwords ▸ Export Logins, then import the CSV."
    private const val SAFARI_PASSWORD_NOTE =
        "Safari keeps passwords in the login keychain, which asks per item. Use File ▸ Export ▸ Passwords, " +
            "then import the CSV."
    private const val UNREADABLE_PASSWORD_NOTE =
        "Saved passwords couldn't be read - the browser may be running, or its database may have moved."
    private const val WINDOWS_PASSWORD_NOTE =
        "Saved passwords can't be read on Windows yet. Export a CSV from the browser instead."

    /**
     * Every detected profile with what it can contribute.
     *
     * Counts only — nothing is decrypted, so this is safe to run on dialog open.
     */
    suspend fun scan(): List<DetectedBrowser> =
        withContext(Dispatchers.IO) {
            BrowserDetector
                .detectProfiles()
                // Concurrently: each Chromium profile costs a full Bookmarks
                // parse plus a Login Data copy, and doing ten sequentially is
                // the whole "Looking for installed browsers…" wait.
                .map { profile -> async { DetectedBrowser(profile, capabilitiesOf(profile)) } }
                .awaitAll()
                .filter { detected ->
                    // Browsers create profile directories eagerly, so several
                    // "Profile N" entries typically hold nothing at all. Drop
                    // those, but keep any whose count is *unknown* rather than
                    // zero — Safari reports null when macOS blocked the read,
                    // and the user needs to see why rather than see it vanish.
                    val bookmarks = detected.capabilities.bookmarkCount
                    val passwords = detected.capabilities.passwordCount
                    bookmarks == null || passwords == null || bookmarks > 0 || passwords > 0
                }
        }

    private fun capabilitiesOf(profile: BrowserProfile): BrowserCapabilities =
        when (profile.family) {
            BrowserFamily.CHROMIUM -> {
                // Counted once: each call copies the Login Data database.
                val logins = if (BrowserDetector.isWindows) null else ChromiumPasswordReader.count(profile)
                BrowserCapabilities(
                    bookmarkCount = countQuietly { ChromiumBookmarkReader.read(profile).size },
                    passwordCount = logins,
                    passwordNote =
                        when {
                            BrowserDetector.isWindows -> WINDOWS_PASSWORD_NOTE
                            logins == null -> UNREADABLE_PASSWORD_NOTE
                            else -> null
                        },
                )
            }

            BrowserFamily.FIREFOX -> {
                BrowserCapabilities(
                    bookmarkCount = countQuietly { FirefoxBookmarkReader.read(profile).size },
                    passwordCount = null,
                    passwordNote = FIREFOX_PASSWORD_NOTE,
                )
            }

            BrowserFamily.SAFARI -> {
                // Safari's directory is TCC-protected, so a failure here means
                // "macOS wouldn't let us look", not "there is nothing there" —
                // and the user can act on that, so keep the reason.
                val attempt = runCatching { SafariBookmarkReader.read(profile).size }
                BrowserCapabilities(
                    bookmarkCount = attempt.getOrNull(),
                    passwordCount = null,
                    bookmarkNote = attempt.exceptionOrNull()?.message,
                    passwordNote = SAFARI_PASSWORD_NOTE,
                )
            }
        }

    /** Counting must never break the picker; an unreadable profile reports null. */
    private fun countQuietly(block: () -> Int): Int? =
        runCatching(block)
            .onFailure { error ->
                logger.debug(
                    LogCategory.FILE,
                    "Could not count items for a browser profile",
                    mapOf("reason" to (error::class.simpleName ?: "unknown")),
                )
            }.getOrNull()

    /**
     * Read everything importable from [profile].
     *
     * This is the call that may prompt for keychain access. A password failure
     * does not discard the bookmarks: the reason is returned in [Result.note]
     * so the dialog can explain the partial outcome.
     */
    suspend fun read(
        profile: BrowserProfile,
        includePasswords: Boolean,
    ): Result =
        withContext(Dispatchers.IO) {
            val bookmarks =
                runCatching { readBookmarks(profile) }
                    .getOrElse { error ->
                        logger.warn(
                            LogCategory.FILE,
                            "Bookmark read failed",
                            mapOf("browser" to profile.browserName, "reason" to (error.message ?: "unknown")),
                        )
                        return@withContext Result(
                            preview = ImportPreview(),
                            note = error.message ?: "That browser's bookmarks couldn't be read.",
                        )
                    }

            if (!includePasswords || profile.family != BrowserFamily.CHROMIUM) {
                return@withContext Result(ImportPreview(bookmarks = bookmarks))
            }

            val read =
                runCatching { ChromiumPasswordReader.read(profile) }
                    .getOrElse { error ->
                        // Bookmarks still import; say why passwords didn't.
                        return@withContext Result(
                            preview = ImportPreview(bookmarks = bookmarks),
                            note = error.message ?: "That browser's passwords couldn't be read.",
                        )
                    }

            // Entries that decrypt to nothing are usually sealed by a desktop
            // keyring (Linux v11), which this cannot open. Without saying so the
            // user sees "70 passwords" in the picker and then a review screen
            // with bookmarks only and no explanation — the same silent shape as
            // the keychain-service-name bug.
            val sealedNote =
                if (read.undecryptable > 0) {
                    "${read.undecryptable} saved passwords could not be decrypted - they are likely sealed by " +
                        "your desktop keyring. Export a CSV from the browser to import those."
                } else {
                    null
                }

            Result(
                preview = ImportPreview(passwords = read.passwords, bookmarks = bookmarks),
                note = sealedNote,
            )
        }

    private fun readBookmarks(profile: BrowserProfile): List<ImportedBookmark> =
        when (profile.family) {
            BrowserFamily.CHROMIUM -> ChromiumBookmarkReader.read(profile)
            BrowserFamily.FIREFOX -> FirefoxBookmarkReader.read(profile)
            BrowserFamily.SAFARI -> SafariBookmarkReader.read(profile)
        }

    /** What a profile yielded, plus any partial-failure explanation. */
    data class Result(
        val preview: ImportPreview,
        val note: String? = null,
    )
}
