package ai.rever.boss.services.importer.browser

import ai.rever.boss.services.importer.ImportedBookmark
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reads `~/Library/Safari/Bookmarks.plist`.
 *
 * The file is a *binary* plist. Rather than take a plist-parsing dependency,
 * this shells out to `plutil`, which ships with macOS, and parses the JSON it
 * emits.
 *
 * Note that `~/Library/Safari` is protected by TCC: unless BOSS has Full Disk
 * Access the read fails with a permission error, which [read] surfaces rather
 * than swallowing.
 */
object SafariBookmarkReader {
    private val logger = BossLogger.forComponent("SafariBookmarkReader")
    private val json = Json { ignoreUnknownKeys = true }

    private const val PLUTIL_TIMEOUT_SECONDS = 20L

    /** Safari's own names for the two roots users recognise. */
    private val ROOT_LABELS =
        mapOf(
            "BookmarksBar" to "Favorites",
            "BookmarksMenu" to "Bookmarks Menu",
            "com.apple.ReadingList" to "Reading List",
        )

    fun bookmarksFile(profile: BrowserProfile): File = File(profile.directory, "Bookmarks.plist")

    fun canRead(profile: BrowserProfile): Boolean = bookmarksFile(profile).isFile

    /** Raised when macOS denies access to Safari's container. */
    class SafariAccessDeniedException :
        Exception(
            "macOS blocked access to Safari's bookmarks. Grant BOSS Full Disk Access in " +
                "System Settings ▸ Privacy & Security, then try again.",
        )

    fun read(profile: BrowserProfile): List<ImportedBookmark> {
        val file = bookmarksFile(profile)
        if (!file.isFile) return emptyList()

        val converted = runPlutil(file)
        val root = json.parseToJsonElement(converted).jsonObject
        val out = mutableListOf<ImportedBookmark>()
        collect(root, emptyList(), out)
        return out
    }

    /** `plutil -convert json -o -` writes the plist to stdout as JSON. */
    private fun runPlutil(file: File): String {
        val result =
            runProcess(
                listOf("/usr/bin/plutil", "-convert", "json", "-o", "-", file.absolutePath),
                PLUTIL_TIMEOUT_SECONDS,
            )

        if (result.exitCode == TIMED_OUT_EXIT_CODE) error("Reading Safari bookmarks timed out.")

        if (result.exitCode != 0 || result.stdout.isBlank()) {
            logger.warn(
                LogCategory.FILE,
                "plutil could not read Safari bookmarks",
                mapOf("exit" to result.exitCode),
            )
            // A TCC denial surfaces as a permission complaint from plutil, whose
            // wording varies by macOS version — "Operation not permitted",
            // "Permission denied", and "you don't have permission to view it"
            // have all been observed. Match the one word they share rather than
            // any single phrasing.
            if (result.stderr.contains("permission", ignoreCase = true)) {
                throw SafariAccessDeniedException()
            }
            error("Safari's bookmarks file could not be read.")
        }
        return result.stdout
    }

    /**
     * Walk the plist tree.
     *
     * A leaf carries `URLString` plus a `URIDictionary.title`; a list carries
     * `Title` (or a well-known root name in `Title`/the dict key) and `Children`.
     */
    private fun collect(
        node: JsonObject,
        path: List<String>,
        out: MutableList<ImportedBookmark>,
    ) {
        val children = node["Children"] as? JsonArray ?: return

        children.forEach { child ->
            val obj = child as? JsonObject ?: return@forEach
            when (obj["WebBookmarkType"]?.jsonPrimitive?.contentOrNull) {
                "WebBookmarkTypeLeaf" -> {
                    val url = obj["URLString"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    // Same gate as every other reader: Safari otherwise imports
                    // javascript: bookmarklets and x-apple-* entries that no
                    // other source does.
                    if (isImportableUrl(url)) {
                        val title =
                            obj["URIDictionary"]
                                ?.jsonObject
                                ?.get("title")
                                ?.jsonPrimitive
                                ?.contentOrNull
                                .orEmpty()
                        out.add(
                            ImportedBookmark(
                                title = title.ifBlank { url },
                                url = url,
                                folder = path.joinToString("/").ifEmpty { null },
                            ),
                        )
                    }
                }

                "WebBookmarkTypeList" -> {
                    val raw =
                        obj["Title"]?.jsonPrimitive?.contentOrNull
                            ?: obj["WebBookmarkIdentifier"]?.jsonPrimitive?.contentOrNull
                            ?: "Untitled"
                    collect(obj, path + (ROOT_LABELS[raw] ?: raw), out)
                }
            }
        }
    }
}
