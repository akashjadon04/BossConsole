package ai.rever.boss.filetypes

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One group of things BOSS can be made the default handler for, as the Settings
 * screen and the registration code see it.
 *
 * Categories, not individual types: BOSS claims 83 extensions and 56 macOS UTIs,
 * and a screen with 83 rows is not a screen anybody sets. What the user actually
 * decides is "should BOSS open my markdown" - five such decisions, each of which
 * expands to the list of types underneath it.
 */
@Serializable
internal data class FileTypeCategory(
    val id: String,
    val displayName: String,
    val description: String,
    /** URL schemes, for the browser-shaped categories. Empty for file types. */
    val schemes: List<String> = emptyList(),
    /** macOS UTIs this category claims that no extension row names (`public.url`). */
    val extraContentTypes: List<String> = emptyList(),
    /** Linux MIME types, for `xdg-mime` and the `.desktop` file. */
    val mimeTypes: List<String> = emptyList(),
    val systemTypeRank: String = "Default",
)

/** One extension, and how macOS is asked about it. See `boss-file-types.json`. */
@Serializable
internal data class FileTypeExtension(
    val ext: String,
    val language: String,
    val category: String,
    /** The system UTI claimed as-is, or null when BOSS exports its own type for this extension. */
    val systemType: String? = null,
    /** A system UTI this extension resolves to that BOSS deliberately refuses. Recorded, never claimed. */
    val rejectedSystemType: String? = null,
)

/** The parsed `boss-file-types.json`. */
@Serializable
internal data class FileTypeTable(
    val exportedTypePrefix: String,
    val exportedTypeSuffix: String,
    val categories: List<FileTypeCategory>,
    val languageNames: Map<String, String> = emptyMap(),
    val extensions: List<FileTypeExtension>,
    /** The resource's own documentation. Declared so it round-trips rather than being an unknown key. */
    @SerialName("\$comment")
    val comment: List<String> = emptyList(),
) {
    /**
     * Every macOS type [categoryId] claims: the system UTIs its extensions
     * resolve to, its extension-less extras, and the types BOSS exports itself.
     *
     * This is the list the "Set" button walks and the status check reduces over,
     * so it must match what the plist declared exactly - hence the exported
     * identifiers being built from the resource's own prefix and suffix rather
     * than from a constant that could drift from `buildSrc/BossFileTypes`.
     */
    fun contentTypesFor(categoryId: String): List<String> {
        val category = categories.firstOrNull { it.id == categoryId } ?: return emptyList()
        val system =
            extensions
                .filter { it.category == categoryId }
                .mapNotNull { it.systemType }
        return (system + category.extraContentTypes + exportedTypeIdsFor(categoryId)).distinct()
    }

    /**
     * The UTIs BOSS exports for [categoryId], one per language that has any
     * extension without a system UTI.
     *
     * Grouped by language, identically to the generator: the plist declares
     * `ai.rever.boss.kotlin-source` once for `.kt` and `.kts`, so asking about
     * one identifier per extension would ask about types that do not exist.
     */
    fun exportedTypeIdsFor(categoryId: String): List<String> =
        extensions
            .filter { it.category == categoryId && it.systemType == null }
            .map { it.language }
            .distinct()
            .map { language -> "$exportedTypePrefix.$language$exportedTypeSuffix" }

    fun extensionsFor(categoryId: String): List<String> = extensions.filter { it.category == categoryId }.map { it.ext }

    fun schemesFor(categoryId: String): List<String> = categories.firstOrNull { it.id == categoryId }?.schemes.orEmpty()

    fun mimeTypesFor(categoryId: String): List<String> = categoryOrNull(categoryId)?.mimeTypes.orEmpty()

    fun categoryOrNull(categoryId: String): FileTypeCategory? = categories.firstOrNull { it.id == categoryId }
}

/**
 * Loads the shared file-type table from the classpath.
 *
 * Same resource `buildSrc/BossFileTypes` reads at build time to generate the
 * macOS plist declarations, so the app cannot ask Launch Services about a type
 * the bundle never declared - the failure mode that would produce is a Settings
 * row that reports "not the default" forever and a button that cannot fix it.
 *
 * Parsed once, lazily. A parse failure yields an empty table and a logged error
 * rather than a throw: this is read from the Settings screen and from startup
 * detection, and neither should be able to take the app down over a resource
 * problem. Every consumer treats an empty table as "no categories to offer",
 * which shows an explanatory empty state instead of a broken one.
 */
internal object FileTypeCategories {
    private const val RESOURCE_NAME = "boss-file-types.json"

    private val logger = BossLogger.forComponent("FileTypeCategories")

    private val json =
        Json {
            // The resource carries a "$comment" array documenting itself, and
            // more fields will be added to it before every consumer is updated.
            ignoreUnknownKeys = true
        }

    private val empty =
        FileTypeTable(
            exportedTypePrefix = "",
            exportedTypeSuffix = "",
            categories = emptyList(),
            extensions = emptyList(),
        )

    val table: FileTypeTable by lazy { load() }

    val categories: List<FileTypeCategory> get() = table.categories

    /** True when the table loaded and has something to offer. */
    fun isAvailable(): Boolean = table.categories.isNotEmpty()

    private fun load(): FileTypeTable =
        try {
            val text =
                FileTypeCategories::class.java.classLoader
                    ?.getResourceAsStream(RESOURCE_NAME)
                    ?.use { it.readBytes().decodeToString() }
            if (text == null) {
                logger.error(
                    LogCategory.SYSTEM,
                    "File-type table resource not found",
                    mapOf("resource" to RESOURCE_NAME),
                )
                empty
            } else {
                json.decodeFromString<FileTypeTable>(text).also { parsed ->
                    logger.debug(
                        LogCategory.SYSTEM,
                        "Loaded file-type table",
                        mapOf("categories" to parsed.categories.size, "extensions" to parsed.extensions.size),
                    )
                }
            }
        } catch (e: Exception) {
            logger.error(LogCategory.SYSTEM, "Could not parse the file-type table", error = e)
            empty
        }
}
