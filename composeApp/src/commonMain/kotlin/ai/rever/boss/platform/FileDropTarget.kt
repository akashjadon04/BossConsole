package ai.rever.boss.platform

import androidx.compose.ui.Modifier

/**
 * Accept files dropped onto this composable, from anywhere the OS can drag from.
 *
 * The transfer is a plain `java.awt.datatransfer.DataFlavor.javaFileListFlavor`, which is what
 * Finder, Explorer and every other desktop app publishes when dragging a file. That is
 * deliberate: it means the same drop target serves a drag out of Finder and a drag out of a
 * BOSS sidebar, with no BOSS-specific transfer type for a plugin to have to know about.
 *
 * [onFilesDropped] receives absolute paths and runs on the UI thread.
 */
expect fun Modifier.bossFileDropTarget(onFilesDropped: (List<String>) -> Unit): Modifier
