package ai.rever.boss.services.importer.browser

import ai.rever.boss.services.importer.ImportedPassword
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Reads saved logins out of a Chromium profile's `Login Data` database.
 *
 * Rows live in `logins`: `origin_url`, `username_value`, and an encrypted
 * `password_value` — see [ChromiumCrypto] for how that is unsealed.
 */
object ChromiumPasswordReader {
    private val logger = BossLogger.forComponent("ChromiumPasswordReader")

    fun loginDataFile(profile: BrowserProfile): File = File(profile.directory, "Login Data")

    fun canRead(profile: BrowserProfile): Boolean = loginDataFile(profile).isFile

    /**
     * How many logins the profile holds.
     *
     * Counting needs no decryption, so the picker can show a number without
     * triggering a keychain prompt.
     */
    fun count(profile: BrowserProfile): Int? {
        val file = loginDataFile(profile)
        if (!file.isFile) return 0

        return runCatching {
            SqliteSnapshot.read(file) { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT COUNT(*) FROM logins WHERE blacklisted_by_user = 0").use { rs ->
                        if (rs.next()) rs.getInt(1) else 0
                    }
                }
            }
        }.getOrElse { error ->
            // null, not 0. A locked database, a renamed column, or sqlite-jdbc
            // failing to load would otherwise read as "this browser has no saved
            // passwords" and the profile would silently disappear from the
            // picker — the same shape as the keychain-service-name bug.
            logger.warn(
                LogCategory.AUTH,
                "Could not count logins for a browser profile",
                mapOf(
                    "browser" to profile.browserName,
                    "reason" to (error.message ?: error::class.simpleName.orEmpty()),
                ),
            )
            null
        }
    }

    /**
     * Decrypt every saved login.
     *
     * Prompts for keychain access on macOS the first time — that prompt is the
     * consent gate for reading another application's credentials.
     *
     * @throws ChromiumCrypto.UnsupportedPlatformException on Windows
     * @throws ChromiumCrypto.KeyUnavailableException if the key can't be read
     */

    /** Decrypted logins, plus how many could not be read and why. */
    data class Result(
        val passwords: List<ImportedPassword>,
        val undecryptable: Int,
    )

    fun read(profile: BrowserProfile): Result {
        val file = loginDataFile(profile)
        if (!file.isFile) return Result(emptyList(), 0)

        val key = ChromiumCrypto.deriveKey(profile.browserName)

        val rows =
            SqliteSnapshot.read(file) { connection ->
                val sql =
                    """
                    SELECT origin_url, username_value, password_value
                    FROM logins
                    WHERE blacklisted_by_user = 0
                    """.trimIndent()

                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    Triple(
                                        rs.getString(1).orEmpty(),
                                        rs.getString(2).orEmpty(),
                                        rs.getBytes(3) ?: ByteArray(0),
                                    ),
                                )
                            }
                        }
                    }
                }
            }

        var undecryptable = 0
        val out =
            rows.mapNotNull { (url, username, blob) ->
                val password = ChromiumCrypto.decrypt(blob, key)
                if (password.isNullOrEmpty()) {
                    // Blank-password rows are usually passkey-only entries; the
                    // import layer skips them either way.
                    if (blob.isNotEmpty()) undecryptable++
                    null
                } else {
                    ImportedPassword(website = url, username = username, password = password)
                }
            }

        logger.info(
            LogCategory.AUTH,
            "Read Chromium logins",
            mapOf("browser" to profile.browserName, "usable" to out.size, "undecryptable" to undecryptable),
        )
        return Result(out, undecryptable)
    }
}
