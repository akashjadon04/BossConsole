package ai.rever.boss.platform

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.awtTransferable
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.io.File

private val logger = BossLogger.forComponent("FileDropTarget")

/**
 * Most files one drop will open.
 *
 * A select-all in Finder is a single gesture, and every opened file costs a tab (and, for
 * code, an LSP session). The remainder is logged rather than silently discarded.
 */
internal const val MAX_FILES_PER_DROP = 20

actual fun Modifier.bossFileDropTarget(onFilesDropped: (List<String>) -> Unit): Modifier =
    composed {
        // Read through a state holder so the target itself never has to be rebuilt. Keying
        // remember() on the lambda would reallocate and re-register the target on every
        // recomposition where the call site's lambda is not inferred stable.
        val current by rememberUpdatedState(onFilesDropped)
        val target =
            remember {
                object : DragAndDropTarget {
                    override fun onDrop(event: DragAndDropEvent): Boolean {
                        val paths = event.transferableOrNull().filePathsOrEmpty()
                        if (paths.isEmpty()) return false
                        current(paths)
                        return true
                    }
                }
            }
        dragAndDropTarget(
            // Answered for every platform drag crossing this composable, so it stays cheap and
            // only inspects the advertised flavours. (BOSS's own tab drags are pointer-input
            // based, not platform drag-and-drop, so they never reach this at all.)
            shouldStartDragAndDrop = { event -> event.transferableOrNull().carriesFiles() },
            target = target,
        )
    }

@OptIn(ExperimentalComposeUiApi::class)
private fun DragAndDropEvent.transferableOrNull(): Transferable? = runCatching { awtTransferable }.getOrNull()

/** Whether [this] is an OS file drag, without deserialising the payload. */
internal fun Transferable?.carriesFiles(): Boolean =
    runCatching {
        this?.isDataFlavorSupported(DataFlavor.javaFileListFlavor) == true
    }.getOrDefault(false)

/**
 * The dropped **files** as absolute paths, capped at [MAX_FILES_PER_DROP].
 *
 * Directories are dropped on the floor. Finder and Explorer hand a folder over through the
 * same flavour as a file, and the caller routes by extension - so a folder would reach the
 * code editor as though it were a source file. Ignoring it keeps the promise that a drop and
 * a click agree; opening a workspace from a drop is a bigger behaviour than this should
 * invent on its own.
 *
 * Never throws. `getTransferData` reaches into the source application and is documented to, so
 * a malformed or already-revoked drag has to be a no-op rather than an exception on the UI
 * thread. The cast is deliberately loose for the same reason: `as? List<File>` only proves it
 * is a `List`, so a `List<String>` from a misbehaving source raises `ClassCastException`
 * inside the map, which this same catch owns.
 */
@Suppress("TooGenericExceptionCaught") // A drag source is another process; any failure is a no-op.
internal fun Transferable?.filePathsOrEmpty(): List<String> =
    try {
        if (this == null || !isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            emptyList()
        } else {
            @Suppress("UNCHECKED_CAST")
            val entries = (getTransferData(DataFlavor.javaFileListFlavor) as? List<File>).orEmpty()
            val files = entries.filterNot { it.isDirectory }
            if (entries.size > files.size) {
                logger.info(
                    LogCategory.FILE,
                    "Ignored directories in a file drop",
                    mapOf("count" to entries.size - files.size),
                )
            }
            if (files.size > MAX_FILES_PER_DROP) {
                logger.warn(
                    LogCategory.FILE,
                    "Too many files in one drop - opening the first $MAX_FILES_PER_DROP",
                    mapOf("dropped" to files.size),
                )
            }
            files.take(MAX_FILES_PER_DROP).map { it.absolutePath }
        }
    } catch (e: Exception) {
        logger.warn(LogCategory.FILE, "Could not read a dropped file list", error = e)
        emptyList()
    }
