import groovy.json.JsonSlurper
import java.io.File

/**
 * Generates the macOS `Info.plist` file-type declarations from
 * `composeApp/src/desktopMain/resources/boss-file-types.json`.
 *
 * Lives in `buildSrc` rather than inline in `composeApp/build.gradle.kts` for
 * the same reason [DebControl] does: the interesting part is a string transform
 * with rules that are easy to get subtly wrong, and here it can be unit-tested
 * (see `BossFileTypesTest`) without packaging a `.app` and reading its plist
 * back.
 *
 * **Why generate it at all.** BOSS claims 83 extensions - every one its editor
 * has a lexer for. Hand-writing that in the plist guarantees it drifts from
 * `EditorLanguages`, which is the table that decides whether a file BOSS agreed
 * to open can actually be highlighted; the KDoc on `EditorLanguages` already
 * laments that "nothing enforces this at build time" for its sibling tables.
 * One resource, one generator and one drift test closes that for the copy that
 * ships in a plist.
 *
 * **Why two kinds of declaration.** Launch Services can only make an app the
 * default for a *type*, never for a bare extension. 42 of the 83 extensions
 * resolve to a system UTI (`public.python-script`, `net.daringfireball.markdown`
 * and so on) which BOSS claims as-is; that is 31 *distinct* UTIs, because several
 * extensions share one. The other 41 have no system UTI at all -
 * `UTType(filenameExtension:)` answers with a `dyn.*` placeholder, which cannot
 * be set as a default - so BOSS exports its own type for them, grouped by
 * language, exactly as other editors do for `.kt`, `.rs` and `.tsx`.
 */
object BossFileTypes {
    /** Conformance for every type BOSS exports. `public.source-code` already conforms to `public.plain-text`. */
    private const val SOURCE_CODE_CONFORMANCE = "public.source-code"

    data class Category(
        val id: String,
        val displayName: String,
        val description: String,
        val schemes: List<String>,
        val extraContentTypes: List<String>,
        val mimeTypes: List<String>,
        /**
         * `LSHandlerRank` for the *system* types in this category.
         *
         * "Alternate" where the type belongs to somebody else - `public.html` is
         * a browser's, and claiming to own it is both untrue and rude to the
         * user's actual browser. "Default" where BOSS is a reasonable primary
         * choice for a developer machine. Types BOSS exports itself are always
         * "Owner", which is not a per-category decision.
         */
        val systemTypeRank: String,
    )

    data class Extension(
        val ext: String,
        val language: String,
        val category: String,
        /** The system UTI claimed for this extension, or null when BOSS exports its own type. */
        val systemType: String?,
        /** A system UTI this extension resolves to that BOSS deliberately refuses. Recorded, never claimed. */
        val rejectedSystemType: String?,
    )

    data class Table(
        val categories: List<Category>,
        val languageNames: Map<String, String>,
        val extensions: List<Extension>,
        /**
         * How the UTIs BOSS owns are named: `<prefix>.<language><suffix>`.
         *
         * Read from the resource rather than held as a constant here because the
         * running app builds the same identifiers to ask Launch Services about
         * them (`FileTypeCategories`), and the two must agree exactly - a
         * mismatch is an app that declares `ai.rever.boss.kotlin-source` in its
         * plist and then asks the OS about something else, which reports "not
         * the default" forever and cannot be fixed by pressing the button.
         */
        val exportedTypePrefix: String,
        val exportedTypeSuffix: String,
    ) {
        /** Distinct system UTIs claimed by [categoryId], plus that category's extension-less extras. */
        fun systemTypesFor(categoryId: String): List<String> {
            val category = categories.first { it.id == categoryId }
            val fromExtensions =
                extensions
                    .filter { it.category == categoryId }
                    .mapNotNull { it.systemType }
            // Distinct, because several extensions share one UTI (.cpp/.cc/.cxx
            // are all public.c-plus-plus-source) and a repeated entry in
            // LSItemContentTypes is a plist that reads as a mistake.
            return (fromExtensions + category.extraContentTypes).distinct()
        }

        /**
         * Languages BOSS exports a type for, in the order they first appear, with
         * the extensions each one claims.
         *
         * Grouped by language rather than one type per extension so the names
         * Finder shows are meaningful ("Kotlin source", not 41 rows) - and so no
         * two exported types can claim the same extension, which would leave
         * Launch Services to pick between two of BOSS's own declarations.
         */
        fun exportedTypes(): List<ExportedType> =
            extensions
                .filter { it.systemType == null }
                .groupBy { it.language }
                .map { (language, rows) ->
                    ExportedType(
                        identifier = "$exportedTypePrefix.$language$exportedTypeSuffix",
                        description = "${languageNames[language] ?: language} source",
                        extensions = rows.map { it.ext },
                        categoryId = rows.first().category,
                    )
                }

        /** Every macOS type BOSS declares: claimed system UTIs plus its own. */
        fun allContentTypes(): List<String> =
            categories.flatMap { systemTypesFor(it.id) } + exportedTypes().map { it.identifier }
    }

    data class ExportedType(
        val identifier: String,
        val description: String,
        val extensions: List<String>,
        val categoryId: String,
    )

    /** Parses the shared resource. Throws with the offending file named, because a typo here breaks packaging. */
    fun parse(json: String): Table {
        @Suppress("UNCHECKED_CAST")
        val root = JsonSlurper().parseText(json) as Map<String, Any?>

        @Suppress("UNCHECKED_CAST")
        val categories =
            (root["categories"] as List<Map<String, Any?>>).map { entry ->
                Category(
                    id = entry.string("id"),
                    displayName = entry.string("displayName"),
                    description = entry.string("description"),
                    schemes = entry.stringList("schemes"),
                    extraContentTypes = entry.stringList("extraContentTypes"),
                    mimeTypes = entry.stringList("mimeTypes"),
                    systemTypeRank = entry.string("systemTypeRank"),
                )
            }

        @Suppress("UNCHECKED_CAST")
        val extensions =
            (root["extensions"] as List<Map<String, Any?>>).map { entry ->
                Extension(
                    ext = entry.string("ext"),
                    language = entry.string("language"),
                    category = entry.string("category"),
                    systemType = entry["systemType"] as String?,
                    rejectedSystemType = entry["rejectedSystemType"] as String?,
                )
            }

        @Suppress("UNCHECKED_CAST")
        val languageNames = (root["languageNames"] as Map<String, String>)

        val table =
            Table(
                categories = categories,
                languageNames = languageNames,
                extensions = extensions,
                exportedTypePrefix = root.string("exportedTypePrefix"),
                exportedTypeSuffix = root.string("exportedTypeSuffix"),
            )
        validate(table)
        return table
    }

    fun parse(file: File): Table = parse(file.readText())

    /**
     * Refuses a table that would produce a broken plist.
     *
     * At configuration time, so a bad edit fails the build rather than shipping
     * an app that claims a type it cannot open or silently claims nothing.
     */
    private fun validate(table: Table) {
        val categoryIds = table.categories.map { it.id }.toSet()
        require(categoryIds.size == table.categories.size) { "boss-file-types.json has duplicate category ids" }

        table.extensions.forEach { row ->
            require(row.category in categoryIds) {
                "boss-file-types.json: extension .${row.ext} names unknown category '${row.category}'"
            }
            require(row.systemType == null || row.rejectedSystemType == null) {
                "boss-file-types.json: extension .${row.ext} both claims and rejects a system type"
            }
            require(row.language in table.languageNames) {
                "boss-file-types.json: extension .${row.ext} names language '${row.language}' with no display name"
            }
        }

        val duplicateExtensions =
            table.extensions
                .groupingBy { it.ext }
                .eachCount()
                .filterValues { it > 1 }
                .keys
        require(duplicateExtensions.isEmpty()) { "boss-file-types.json has duplicate extensions: $duplicateExtensions" }

        // An exported type claiming an extension another exported type also
        // claims would leave Launch Services choosing between two of BOSS's own
        // declarations for the same file. Grouping by language makes this
        // impossible today; the check is here so a future grouping change cannot
        // reintroduce it silently.
        val claimed = table.exportedTypes().flatMap { it.extensions }
        require(claimed.size == claimed.distinct().size) {
            "boss-file-types.json: two exported types claim the same extension"
        }

        // An exported language must live in exactly one category. The generator
        // groups exported types by language across the WHOLE table, while the
        // running app (FileTypeCategories.exportedTypeIdsFor) groups by language
        // WITHIN a category. Those agree only while no language spans two
        // categories; if one ever did, the single generated UTI would be listed
        // under both, and claiming one category would silently make BOSS the
        // default for the other's extensions too. Sibling of the check above:
        // that one catches a duplicate extension, this catches a duplicate
        // language.
        val languagesPerCategory =
            table.extensions
                .filter { it.systemType == null }
                .groupBy({ it.language }, { it.category })
                .mapValues { (_, categories) -> categories.distinct() }
        val spanning = languagesPerCategory.filterValues { it.size > 1 }
        require(spanning.isEmpty()) {
            "boss-file-types.json: exported language(s) span more than one category, " +
                "which would make one UTI claim extensions in both: $spanning"
        }
    }

    /**
     * The `CFBundleDocumentTypes` array: what BOSS appears in "Open With" for
     * and can be made the default for.
     *
     * One entry per category for the system types it claims, and one per
     * exported language, so the names Finder shows read as file kinds rather
     * than as one undifferentiated blob.
     */
    fun documentTypesXml(table: Table): String {
        val entries = mutableListOf<String>()

        table.categories.forEach { category ->
            val systemTypes = table.systemTypesFor(category.id)
            if (systemTypes.isNotEmpty()) {
                entries += documentTypeEntry(category.displayName, systemTypes, category.systemTypeRank)
            }
        }

        table.exportedTypes().forEach { exported ->
            // Owner, because BOSS declares the type: nothing else on the machine
            // has an opinion about ai.rever.boss.kotlin-source.
            entries += documentTypeEntry(exported.description, listOf(exported.identifier), "Owner")
        }

        return buildString {
            appendLine("<key>CFBundleDocumentTypes</key>")
            appendLine("<array>")
            entries.forEach { append(it) }
            append("</array>")
        }
    }

    private fun documentTypeEntry(
        typeName: String,
        contentTypes: List<String>,
        handlerRank: String,
    ): String =
        buildString {
            appendLine("    <dict>")
            appendLine("        <key>CFBundleTypeName</key>")
            appendLine("        <string>${escape(typeName)}</string>")
            // Editor, not Viewer: BOSS opens these in an editor that can save
            // them. The previous hand-written entry said Viewer for public.html,
            // which told Finder BOSS could only look at it.
            appendLine("        <key>CFBundleTypeRole</key>")
            appendLine("        <string>Editor</string>")
            appendLine("        <key>LSHandlerRank</key>")
            appendLine("        <string>${escape(handlerRank)}</string>")
            appendLine("        <key>LSItemContentTypes</key>")
            appendLine("        <array>")
            contentTypes.forEach { appendLine("            <string>${escape(it)}</string>") }
            appendLine("        </array>")
            appendLine("    </dict>")
        }

    /**
     * The `UTExportedTypeDeclarations` array: the types BOSS invents for
     * extensions macOS has never heard of.
     *
     * Without these, `.kt`, `.rs`, `.go` and 38 others have only a `dyn.*`
     * placeholder UTI, and `LSSetDefaultRoleHandlerForContentType` cannot be
     * given a `dyn.*` type - so "make BOSS the default for Kotlin files" had no
     * type to name and could not be expressed at all.
     */
    fun exportedTypesXml(table: Table): String {
        val exported = table.exportedTypes()
        if (exported.isEmpty()) return ""

        return buildString {
            appendLine("<key>UTExportedTypeDeclarations</key>")
            appendLine("<array>")
            exported.forEach { type ->
                appendLine("    <dict>")
                appendLine("        <key>UTTypeIdentifier</key>")
                appendLine("        <string>${escape(type.identifier)}</string>")
                appendLine("        <key>UTTypeDescription</key>")
                appendLine("        <string>${escape(type.description)}</string>")
                appendLine("        <key>UTTypeConformsTo</key>")
                appendLine("        <array>")
                appendLine("            <string>$SOURCE_CODE_CONFORMANCE</string>")
                appendLine("        </array>")
                appendLine("        <key>UTTypeTagSpecification</key>")
                appendLine("        <dict>")
                appendLine("            <key>public.filename-extension</key>")
                appendLine("            <array>")
                type.extensions.forEach { appendLine("                <string>${escape(it)}</string>") }
                appendLine("            </array>")
                appendLine("        </dict>")
                appendLine("    </dict>")
            }
            append("</array>")
        }
    }

    /**
     * The `CFBundleURLTypes` array: the URL schemes BOSS handles.
     *
     * `boss` is BOSS's own scheme and is not in the table; the rest come from the
     * categories, so adding a scheme is a resource edit rather than a plist edit.
     */
    fun urlTypesXml(
        table: Table,
        ownScheme: String,
        urlName: String,
    ): String {
        val schemes = listOf(ownScheme) + table.categories.flatMap { it.schemes }.distinct()
        return buildString {
            appendLine("<key>CFBundleURLTypes</key>")
            appendLine("<array>")
            appendLine("    <dict>")
            appendLine("        <key>CFBundleURLName</key>")
            appendLine("        <string>${escape(urlName)}</string>")
            appendLine("        <key>CFBundleURLSchemes</key>")
            appendLine("        <array>")
            schemes.forEach { appendLine("            <string>${escape(it)}</string>") }
            appendLine("        </array>")
            appendLine("    </dict>")
            append("</array>")
        }
    }

    /**
     * XML-escapes a plist string value.
     *
     * The inputs are ASCII identifiers from a controlled table, so this is not
     * load-bearing today; it is here so that adding a language called `C++/CLI`
     * or a description with an ampersand produces a valid plist instead of an app
     * that will not launch.
     */
    private fun escape(value: String): String =
        value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")

    private fun Map<String, Any?>.string(key: String): String =
        this[key] as? String
            ?: error("boss-file-types.json: missing or non-string '$key' in $this")

    @Suppress("UNCHECKED_CAST")
    private fun Map<String, Any?>.stringList(key: String): List<String> = (this[key] as? List<String>) ?: emptyList()
}
