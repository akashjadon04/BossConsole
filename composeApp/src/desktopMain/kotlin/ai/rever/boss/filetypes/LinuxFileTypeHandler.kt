package ai.rever.boss.filetypes

import ai.rever.boss.utils.DefaultHandlerState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Linux MIME associations for a [FileTypeCategory], through `xdg-mime`.
 *
 * The `.desktop` file itself is written by `LinuxDefaultBrowserHandler`, which
 * owns it; this asks it to include every category's MIME types (see
 * [allMimeTypes]) and then points each type at it.
 *
 * **Only MIME types freedesktop actually defines are listed** in
 * `boss-file-types.json`. `xdg-mime default` accepts any string, and pointing it
 * at an invented type such as `text/x-kotlin` silently records an association no
 * file will ever match, because nothing maps `.kt` to that type. Making those
 * work needs a `shared-mime-info` XML package installed alongside the app, which
 * is a bigger change than this one and is not attempted: what a Linux user gets
 * here is the schemes plus the languages the distribution already has types for.
 */
internal object LinuxFileTypeHandler {
    private val logger = BossLogger.forComponent("LinuxFileTypeHandler")

    private const val PROCESS_TIMEOUT_SECONDS = 15L

    private const val DESKTOP_FILE_NAME = "boss.desktop"

    /**
     * Every MIME type across every category, for the `.desktop` `MimeType=` line.
     *
     * The desktop entry has to declare a type before `xdg-mime default` will
     * associate it; declaring the union once is simpler than rewriting the
     * desktop file per category, and harmless - declaring support is not the same
     * as being the default.
     */
    fun allMimeTypes(): List<String> = FileTypeCategories.categories.flatMap { it.mimeTypes }.distinct()

    /** Points every MIME type in [category] at BOSS. True when all of them were accepted. */
    fun setDefault(category: FileTypeCategory): Boolean {
        val mimeTypes = FileTypeCategories.table.mimeTypesFor(category.id)
        if (mimeTypes.isEmpty()) return true

        // fold rather than all, so one unknown type does not skip the rest.
        val ok =
            mimeTypes.fold(true) { acc, mimeType ->
                runSucceeds(listOf("xdg-mime", "default", DESKTOP_FILE_NAME, mimeType)) && acc
            }

        logger.info(
            LogCategory.SYSTEM,
            if (ok) "Set Linux MIME associations" else "Some Linux MIME associations were refused",
            mapOf("category" to category.id, "types" to mimeTypes.size),
        )
        return ok
    }

    /**
     * Who owns [category] according to `xdg-mime query default`.
     *
     * There is no Linux equivalent of the engine-bundle collision, so the answer
     * is only ever [DefaultHandlerState.Ours] or [DefaultHandlerState.Other]:
     * the engine on Linux is a plain executable with no desktop entry, so it can
     * never be a candidate.
     */
    fun statusOf(category: FileTypeCategory): DefaultHandlerState {
        val mimeTypes = FileTypeCategories.table.mimeTypesFor(category.id)
        if (mimeTypes.isEmpty()) return DefaultHandlerState.Other(null)

        val states =
            mimeTypes.map { mimeType ->
                val owner = queryDefault(mimeType)
                when {
                    owner == null -> DefaultHandlerState.Other(null)
                    owner.equals(DESKTOP_FILE_NAME, ignoreCase = true) -> DefaultHandlerState.Ours
                    else -> DefaultHandlerState.Other(owner)
                }
            }

        return DefaultHandlerState.reduce(states)
    }

    private fun queryDefault(mimeType: String): String? =
        try {
            val process =
                ProcessBuilder("xdg-mime", "query", "default", mimeType)
                    .redirectErrorStream(true)
                    .start()
            // Read on another thread so the waitFor timeout can actually fire.
            // Reading to EOF first and waiting afterwards - which this did - means
            // an xdg-mime that never closes stdout hangs forever and the 15 s bound
            // is decorative.
            val output =
                CompletableFuture.supplyAsync {
                    process.inputStream.bufferedReader().use { it.readText().trim() }
                }
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() != 0) {
                null
            } else {
                output.get(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS).ifEmpty { null }
            }
        } catch (e: Exception) {
            // xdg-utils absent is normal on a minimal install, and it is the
            // reason a status can be unknown rather than false.
            logger.debug(
                LogCategory.SYSTEM,
                "Could not query the MIME association",
                mapOf("mimeType" to mimeType, "reason" to (e.message ?: "unknown")),
            )
            null
        }

    private fun runSucceeds(command: List<String>): Boolean =
        try {
            // DISCARD rather than redirectErrorStream(true) with nobody reading:
            // an undrained pipe deadlocks the child as soon as it fills, and the
            // timeout cannot rescue a child that never exits.
            val process =
                ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                logger.warn(LogCategory.SYSTEM, "xdg command timed out", mapOf("command" to command.joinToString(" ")))
                false
            } else {
                process.exitValue() == 0
            }
        } catch (e: Exception) {
            logger.warn(LogCategory.SYSTEM, "Could not run an xdg command", error = e)
            false
        }
}
