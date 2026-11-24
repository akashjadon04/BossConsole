package ai.rever.boss.services.importer.browser

import ai.rever.boss.services.importer.ImportedBookmark
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/**
 * Reads a Chromium profile's `Bookmarks` file.
 *
 * That file is plain JSON — no database, no encryption — so this needs nothing
 * but the serialization library the host already has:
 *
 * ```json
 * { "roots": { "bookmark_bar": { "children": [
 *     {"type": "url",    "name": "Example", "url": "https://example.com"},
 *     {"type": "folder", "name": "Work", "children": [ … ]}
 * ]}}}
 * ```
 */
object ChromiumBookmarkReader {
    private val json = Json { ignoreUnknownKeys = true }

    /** Root keys, mapped to the folder name they present as. */
    private val ROOT_LABELS =
        mapOf(
            "bookmark_bar" to "Bookmarks Bar",
            "other" to "Other Bookmarks",
            "synced" to "Mobile Bookmarks",
        )

    fun bookmarksFile(profile: BrowserProfile): File = File(profile.directory, "Bookmarks")

    fun canRead(profile: BrowserProfile): Boolean = bookmarksFile(profile).isFile

    /** Every bookmark in the profile, folder paths preserved. */
    fun read(profile: BrowserProfile): List<ImportedBookmark> {
        val file = bookmarksFile(profile)
        if (!file.isFile) return emptyList()

        val roots =
            json
                .parseToJsonElement(file.readText())
                .jsonObject["roots"]
                ?.jsonObject
                .orEmpty()

        val out = mutableListOf<ImportedBookmark>()
        roots.forEach { (key, value) ->
            // "sync_transaction_version" and friends sit alongside the real roots.
            val node = value as? JsonObject ?: return@forEach
            val label = ROOT_LABELS[key] ?: return@forEach
            collect(node, listOf(label), out)
        }
        return out
    }

    private fun collect(
        node: JsonObject,
        path: List<String>,
        out: MutableList<ImportedBookmark>,
    ) {
        val children = node["children"] as? JsonArray ?: return

        children.forEach { child ->
            val obj = child as? JsonObject ?: return@forEach
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "url" -> {
                    val url = obj["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (url.isNotBlank() && isImportableUrl(url)) {
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        out.add(
                            ImportedBookmark(
                                title = name.ifBlank { url },
                                url = url,
                                folder = path.joinToString("/"),
                            ),
                        )
                    }
                }

                "folder" -> {
                    val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    collect(obj, path + name.ifBlank { "Untitled" }, out)
                }
            }
        }
    }
}
