package ai.rever.boss.services.importer.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File

/**
 * Finds browser profiles installed for the current user.
 *
 * Detection is by data directory rather than by application bundle: a browser
 * that is installed but has never run has nothing to import, and one that has
 * been uninstalled may still have a profile worth reading.
 */
object BrowserDetector {
    private val logger = BossLogger.forComponent("BrowserDetector")

    private val home: String get() = System.getProperty("user.home").orEmpty()

    private val os: String get() = System.getProperty("os.name").orEmpty().lowercase()

    val isMac: Boolean get() = os.contains("mac")
    val isWindows: Boolean get() = os.contains("win")

    /**
     * Chromium-family data directories, per OS.
     *
     * The value is the directory that *contains* the profile directories
     * ("Default", "Profile 1", …), not a profile itself.
     */
    private fun chromiumRoots(): List<Pair<String, File>> {
        val mac = "$home/Library/Application Support"
        val localAppData = System.getenv("LOCALAPPDATA").orEmpty()
        val appData = System.getenv("APPDATA").orEmpty()
        val config = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.config"

        val candidates: List<Pair<String, String>> =
            when {
                isMac -> {
                    listOf(
                        "Google Chrome" to "$mac/Google/Chrome",
                        "Google Chrome Beta" to "$mac/Google/Chrome Beta",
                        "Google Chrome Canary" to "$mac/Google/Chrome Canary",
                        "Microsoft Edge" to "$mac/Microsoft Edge",
                        "Brave" to "$mac/BraveSoftware/Brave-Browser",
                        "Vivaldi" to "$mac/Vivaldi",
                        "Opera" to "$mac/com.operasoftware.Opera",
                        "Arc" to "$mac/Arc/User Data",
                        "Chromium" to "$mac/Chromium",
                    )
                }

                isWindows -> {
                    listOf(
                        "Google Chrome" to "$localAppData\\Google\\Chrome\\User Data",
                        "Microsoft Edge" to "$localAppData\\Microsoft\\Edge\\User Data",
                        "Brave" to "$localAppData\\BraveSoftware\\Brave-Browser\\User Data",
                        "Vivaldi" to "$localAppData\\Vivaldi\\User Data",
                        "Opera" to "$appData\\Opera Software\\Opera Stable",
                        "Chromium" to "$localAppData\\Chromium\\User Data",
                    )
                }

                else -> {
                    listOf(
                        "Google Chrome" to "$config/google-chrome",
                        "Microsoft Edge" to "$config/microsoft-edge",
                        "Brave" to "$config/BraveSoftware/Brave-Browser",
                        "Vivaldi" to "$config/vivaldi",
                        "Opera" to "$config/opera",
                        "Chromium" to "$config/chromium",
                    )
                }
            }

        return candidates.mapNotNull { (name, path) ->
            val dir = File(path)
            if (dir.isDirectory) name to dir else null
        }
    }

    /** Firefox profile roots, per OS. */
    private fun firefoxRoot(): File? {
        val path =
            when {
                isMac -> "$home/Library/Application Support/Firefox/Profiles"
                isWindows -> "${System.getenv("APPDATA").orEmpty()}\\Mozilla\\Firefox\\Profiles"
                else -> "$home/.mozilla/firefox"
            }
        return File(path).takeIf { it.isDirectory }
    }

    /** Safari's data directory. macOS only, and usually TCC-protected. */
    private fun safariRoot(): File? = if (isMac) File("$home/Library/Safari").takeIf { it.isDirectory } else null

    /**
     * Every profile we can see.
     *
     * Profiles with neither bookmarks nor logins are dropped — showing an empty
     * "Chrome" row that imports nothing is worse than not listing it.
     */
    fun detectProfiles(): List<BrowserProfile> {
        val found = mutableListOf<BrowserProfile>()

        chromiumRoots().forEach { (name, root) ->
            // "Default" plus any "Profile N"; a root with neither is a browser
            // that was installed but never run.
            val profileDirs =
                root
                    .listFiles { f: File -> f.isDirectory }
                    .orEmpty()
                    .filter { it.name == "Default" || it.name.startsWith("Profile ") }
                    .sortedBy { it.name }

            profileDirs.forEach { dir ->
                if (File(dir, "Bookmarks").isFile || File(dir, "Login Data").isFile) {
                    found.add(
                        BrowserProfile(
                            browserName = name,
                            family = BrowserFamily.CHROMIUM,
                            profileName = dir.name.takeIf { profileDirs.size > 1 || it != "Default" },
                            directory = dir,
                        ),
                    )
                }
            }
        }

        firefoxRoot()?.let { root ->
            val profileDirs =
                root
                    .listFiles { f: File -> f.isDirectory }
                    .orEmpty()
                    .filter { File(it, "places.sqlite").isFile || File(it, "logins.json").isFile }
                    .sortedBy { it.name }

            profileDirs.forEach { dir ->
                found.add(
                    BrowserProfile(
                        browserName = "Firefox",
                        family = BrowserFamily.FIREFOX,
                        // Directory names look like "abc123.default-release"; the
                        // readable part is after the dot.
                        profileName = dir.name.substringAfter('.', dir.name).takeIf { profileDirs.size > 1 },
                        directory = dir,
                    ),
                )
            }
        }

        safariRoot()?.let { root ->
            if (File(root, "Bookmarks.plist").isFile) {
                found.add(
                    BrowserProfile(
                        browserName = "Safari",
                        family = BrowserFamily.SAFARI,
                        profileName = null,
                        directory = root,
                    ),
                )
            }
        }

        logger.info(
            LogCategory.FILE,
            "Browser profile detection finished",
            mapOf("profiles" to found.size, "browsers" to found.map { it.browserName }.distinct().size),
        )
        return found
    }
}
