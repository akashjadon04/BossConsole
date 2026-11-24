package ai.rever.boss.filetypes

import ai.rever.boss.utils.BOSS_MACOS_BUNDLE_ID
import ai.rever.boss.utils.DefaultHandlerState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.mac.LaunchServices

/**
 * Reads and writes the macOS default handler for a whole [FileTypeCategory].
 *
 * A category is several types - "Source code and config" is 26 system UTIs plus
 * 24 BOSS exports - and Launch Services stores an answer per type, so both the
 * status and the set are a fold over the list rather than one call.
 */
internal object MacOSFileTypeHandler {
    private val logger = BossLogger.forComponent("MacOSFileTypeHandler")

    /** Whether the native Launch Services calls can be made at all. */
    fun isAvailable(): Boolean = LaunchServices.isAvailable()

    /**
     * Who owns [category] right now, reduced to one answer.
     *
     * [DefaultHandlerState.OurEngine] outranks [DefaultHandlerState.Other]
     * whenever any type in the category is held by the Chromium engine bundle,
     * because that is the answer with a one-click fix and the one the user needs
     * to be told about. See [DefaultHandlerState].
     */
    fun statusOf(category: FileTypeCategory): DefaultHandlerState {
        val table = FileTypeCategories.table
        val owners =
            table.schemesFor(category.id).map { LaunchServices.defaultHandlerForScheme(it) } +
                table.contentTypesFor(category.id).map { LaunchServices.defaultHandlerForContentType(it) }

        if (owners.isEmpty()) return DefaultHandlerState.Other(null)

        return DefaultHandlerState.reduce(owners.map { DefaultHandlerState.of(it) })
    }

    /**
     * Claims every type and scheme in [category] for BOSS.
     *
     * @return true when the OS accepted all of them. A partial result is
     *   reported as false and left in place rather than rolled back: the types it
     *   did accept are ones the user asked for, and undoing them would hand the
     *   file back to whatever had it with nothing gained.
     */
    fun setDefault(category: FileTypeCategory): Boolean {
        val table = FileTypeCategories.table

        // fold, not all: `all` short-circuits, so one refused type would skip
        // every remaining one in the category and leave most of the user's
        // request undone while reporting a single failure.
        val schemesSet =
            table.schemesFor(category.id).fold(true) { acc, scheme ->
                LaunchServices.setDefaultHandlerForScheme(scheme, BOSS_MACOS_BUNDLE_ID) && acc
            }
        val typesSet =
            table.contentTypesFor(category.id).fold(true) { acc, contentType ->
                LaunchServices.setDefaultHandlerForContentType(contentType, BOSS_MACOS_BUNDLE_ID) && acc
            }

        val ok = schemesSet && typesSet
        logger.info(
            LogCategory.SYSTEM,
            if (ok) "Claimed a file-type category" else "Could not claim every type in a category",
            mapOf("category" to category.id),
        )
        return ok
    }
}
