package ai.rever.boss.services.importer.browser

import ai.rever.boss.services.importer.ImportedBookmark
import java.io.File

/**
 * Reads bookmarks from a Firefox profile's `places.sqlite`.
 *
 * Bookmarks live in `moz_bookmarks` as a parent/child tree; the URLs they point
 * at live in `moz_places`. `type` 1 is a bookmark, 2 a folder. The four roots
 * (ids 1-5) carry internal names, so they are relabelled to what Firefox shows
 * users.
 */
object FirefoxBookmarkReader {
    /** Internal root titles → the names Firefox displays. */
    private val ROOT_LABELS =
        mapOf(
            "toolbar" to "Bookmarks Toolbar",
            "menu" to "Bookmarks Menu",
            "unfiled" to "Other Bookmarks",
            "mobile" to "Mobile Bookmarks",
        )

    private const val TYPE_BOOKMARK = 1
    private const val TYPE_FOLDER = 2

    /**
     * Firefox's tags live in moz_bookmarks like everything else: the tags root
     * holds one folder per tag, and every tagged page is a `type = 1` child
     * pointing at the same moz_places row. Without excluding them a page with
     * three tags imports four times — once real, three times titled with the
     * raw URL under a literal "tags/..." path.
     */
    private const val TAGS_ROOT_GUID = "tags________"

    /** Depth guard: a corrupt profile can contain a parent cycle. */
    private const val MAX_ANCESTOR_HOPS = 32

    private data class Row(
        val id: Long,
        val parent: Long,
        val type: Int,
        val title: String,
        val url: String?,
        val guid: String,
    )

    fun placesFile(profile: BrowserProfile): File = File(profile.directory, "places.sqlite")

    fun canRead(profile: BrowserProfile): Boolean = placesFile(profile).isFile

    fun read(profile: BrowserProfile): List<ImportedBookmark> {
        val file = placesFile(profile)
        if (!file.isFile) return emptyList()

        val rows =
            SqliteSnapshot.read(file) { connection ->
                val sql =
                    """
                    SELECT b.id, b.parent, b.type, IFNULL(b.title, ''), p.url, IFNULL(b.guid, '')
                    FROM moz_bookmarks b
                    LEFT JOIN moz_places p ON b.fk = p.id
                    """.trimIndent()

                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rs ->
                        buildList {
                            while (rs.next()) {
                                add(
                                    Row(
                                        id = rs.getLong(1),
                                        parent = rs.getLong(2),
                                        type = rs.getInt(3),
                                        title = rs.getString(4).orEmpty(),
                                        url = rs.getString(5),
                                        guid = rs.getString(6).orEmpty(),
                                    ),
                                )
                            }
                        }
                    }
                }
            }

        val byId = rows.associateBy { it.id }
        return rows
            .filter {
                it.type == TYPE_BOOKMARK &&
                    !it.url.isNullOrBlank() &&
                    isImportableUrl(it.url) &&
                    !isUnderTagsRoot(it, byId)
            }.map { row ->
                ImportedBookmark(
                    title = row.title.ifBlank { row.url.orEmpty() },
                    url = row.url.orEmpty(),
                    folder = folderPath(row, byId),
                )
            }
    }

    /** True when [row]'s ancestor chain reaches the tags root. */
    private fun isUnderTagsRoot(
        row: Row,
        byId: Map<Long, Row>,
    ): Boolean {
        var current = byId[row.parent]
        var hops = 0
        while (current != null && hops < MAX_ANCESTOR_HOPS) {
            if (current.guid == TAGS_ROOT_GUID) return true
            current = byId[current.parent]
            hops++
        }
        return false
    }

    /** Walk up the parent chain, dropping the unnamed synthetic root. */
    private fun folderPath(
        row: Row,
        byId: Map<Long, Row>,
    ): String? {
        val parts = mutableListOf<String>()
        var current = byId[row.parent]
        // Depth guard: a corrupt profile could contain a parent cycle.
        var hops = 0

        while (current != null && current.type == TYPE_FOLDER && hops < MAX_ANCESTOR_HOPS) {
            val name = ROOT_LABELS[current.title] ?: current.title
            if (name.isNotBlank()) parts.add(name)
            current = byId[current.parent]
            hops++
        }
        return parts.reversed().joinToString("/").ifEmpty { null }
    }
}
