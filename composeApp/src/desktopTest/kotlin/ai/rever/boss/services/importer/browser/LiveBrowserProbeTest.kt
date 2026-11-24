package ai.rever.boss.services.importer.browser

import kotlin.test.Test

/**
 * Reports what the readers can actually see on the machine running the tests.
 *
 * Deliberately assertion-free: what is installed varies per machine and per CI
 * runner, so failing here would mean failing for reasons unrelated to the code.
 * Its value is the printed summary when verifying by hand — it exercises real
 * detection and real parsing against real profiles, which no fixture can.
 *
 * Never prints a credential: counts and names only.
 */
class LiveBrowserProbeTest {
    @Test
    fun `report detected browsers`() {
        // Gated: this reads the developer's real browser profiles and copies
        // their SQLite databases to temp. A plain `./gradlew test` should not
        // touch user data.
        if (System.getenv("BOSS_IMPORT_PROBE") != "1") {
            println("skipped (set BOSS_IMPORT_PROBE=1 to run)")
            return
        }

        val profiles = BrowserDetector.detectProfiles()
        println("=== detected profiles: ${profiles.size} ===")

        profiles.forEach { profile ->
            val bookmarks =
                runCatching {
                    when (profile.family) {
                        BrowserFamily.CHROMIUM -> ChromiumBookmarkReader.read(profile)
                        BrowserFamily.FIREFOX -> FirefoxBookmarkReader.read(profile)
                        BrowserFamily.SAFARI -> SafariBookmarkReader.read(profile)
                    }
                }

            val logins =
                if (profile.family == BrowserFamily.CHROMIUM) {
                    ChromiumPasswordReader.count(profile) ?: -1
                } else {
                    -1
                }

            val bookmarkSummary =
                bookmarks.fold(
                    onSuccess = { list ->
                        val folders = list.mapNotNull { it.folder }.distinct().size
                        "${list.size} bookmarks in $folders folders"
                    },
                    onFailure = { "bookmarks unavailable (${it::class.simpleName}: ${it.message})" },
                )

            // Counts and folder names only. Printing bookmark titles would put
            // the developer's real browsing into test output.
            println("- ${profile.displayName} [${profile.family}] $bookmarkSummary; logins=$logins")
        }
    }

    /**
     * Opt-in: decrypting Chromium logins prompts for keychain access, so this
     * only runs when BOSS_IMPORT_PROBE_PASSWORDS=1. Prints shapes, never values.
     */
    @Test
    fun `report decryptable chromium logins`() {
        if (System.getenv("BOSS_IMPORT_PROBE_PASSWORDS") != "1") {
            println("skipped (set BOSS_IMPORT_PROBE_PASSWORDS=1 to run)")
            return
        }

        BrowserDetector
            .detectProfiles()
            .filter { it.family == BrowserFamily.CHROMIUM && (ChromiumPasswordReader.count(it) ?: 0) > 0 }
            .forEach { profile ->
                val outcome =
                    runCatching { ChromiumPasswordReader.read(profile) }
                        .fold(
                            onSuccess = { result ->
                                val list = result.passwords
                                val withHost = list.count { it.website.startsWith("http") }
                                val withUser = list.count { it.username.isNotBlank() }
                                "decrypted ${list.size} (hosts=$withHost users=$withUser " +
                                    "undecryptable=${result.undecryptable})"
                            },
                            onFailure = { "FAILED ${it::class.simpleName}: ${it.message}" },
                        )
                println("- ${profile.displayName}: $outcome")
            }
    }
}
