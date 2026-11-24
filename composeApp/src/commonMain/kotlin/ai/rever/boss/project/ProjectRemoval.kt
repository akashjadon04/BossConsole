package ai.rever.boss.project

import ai.rever.boss.components.bars.horizontal.StatusMessageManager
import ai.rever.boss.components.plugin.panels.left_top.ProjectState
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.io.File

private val logger = BossLogger.forComponent("ProjectRemoval")

/** Whether removing a recent project also disposes of the folder it points at. */
enum class ProjectRemovalScope {
    /** Forget the project. The folder stays exactly where it is. */
    RECENTS_ONLY,

    /** Forget the project and move its folder to the Trash. */
    RECENTS_AND_FOLDER,
}

/**
 * What a removal actually did, so the caller can say so.
 *
 * The list entry always goes - that is what the button says it does. The folder is the
 * part that can fail, and it fails in ways the user needs told about rather than
 * discovering later that a folder they asked to delete is still on disk.
 */
sealed interface ProjectRemovalResult {
    /** Forgotten, and the folder went to the Trash if it was asked for. */
    data class Removed(
        val trashed: Boolean,
    ) : ProjectRemovalResult

    /** Forgotten, but the folder is still there. [reason] is written for the user. */
    data class FolderKept(
        val reason: String,
    ) : ProjectRemovalResult
}

/**
 * Why [path] must not be moved to the Trash, or null when it may be.
 *
 * Both kinds of refusal - "this system cannot" and "not this folder" - come back through
 * one function because they land in the same place: the checkbox that offers the folder
 * deletion is disabled and shows this text. Two entry points would mean a caller could
 * check one and forget the other, and the cost of forgetting is a deleted directory.
 */
fun trashRefusal(path: String): String? {
    if (!isTrashAvailable()) return NO_TRASH
    return pathTrashRefusal(path)
}

/**
 * The half of [trashRefusal] that only looks at the path.
 *
 * Split out because the other half asks AWT whether this desktop has a Trash, and a
 * headless JVM - which is how the tests run, and how CI runs everything - answers no. With
 * one function the guards below would be unreachable from a test, which is the wrong thing
 * to leave untested: they are what stands between a checkbox and someone's home directory.
 *
 * Deliberately blunt. A recent-projects entry is whatever directory someone once pointed
 * BOSS at, `$HOME` included if they did that.
 */
internal fun pathTrashRefusal(path: String): String? {
    if (path.isBlank()) return "This project has no folder on disk."
    val folder = canonicalOrNull(path)
    return if (folder == null) {
        "That folder cannot be read."
    } else {
        PATH_GUARDS.firstNotNullOfOrNull { guard -> guard.reason.takeIf { guard.refuses(folder) } }
    }
}

private class PathGuard(
    val reason: String,
    val refuses: (File) -> Boolean,
)

/**
 * Ordered so the message matches what the user is most likely looking at: the two that
 * explain a folder that is not there come before the two that explain one BOSS will not
 * touch.
 */
private val PATH_GUARDS =
    listOf(
        PathGuard("That folder is already gone.") { !it.exists() },
        PathGuard("That path is not a folder.") { !it.isDirectory },
        PathGuard("That is a filesystem root.") { it.parentFile == null },
        PathGuard("That is your home folder.") { it == HOME_FOLDER },
    )

/**
 * Canonical, so `~/projects/..` and a symlink pointing at `$HOME` both reach the guards
 * that refuse them. A path that cannot be resolved at all is refused rather than guessed at.
 */
private fun canonicalOrNull(path: String): File? =
    try {
        File(path).canonicalFile
    } catch (e: java.io.IOException) {
        logger.warn(LogCategory.FILE, "Could not canonicalise a project path", mapOf("path" to path), error = e)
        null
    }

/** Resolved once: it costs a syscall and cannot change while the process runs. */
private val HOME_FOLDER: File? by lazy {
    System.getProperty("user.home")?.let { runCatching { File(it).canonicalFile }.getOrNull() }
}

/**
 * Forget [project], and move its folder to the Trash when [scope] says so.
 *
 * The forget happens either way, including when the folder could not be trashed: the user
 * pressed a button that says Remove, and leaving the entry behind because a *second*,
 * optional part failed would be a worse surprise than the entry going. The result says
 * which of the two happened.
 *
 * Moving to the Trash rather than deleting is the whole point of the folder option. There
 * is no path through this file that unlinks anything - a mistake has to stay recoverable
 * from the user's own Trash.
 */
suspend fun removeProject(
    project: Project,
    scope: ProjectRemovalScope,
): ProjectRemovalResult {
    val askedForFolder = scope == ProjectRemovalScope.RECENTS_AND_FOLDER
    val failure = if (askedForFolder) moveFolderToTrash(project.path) else null

    ProjectState.removeRecentProject(project.path)

    return when {
        failure != null -> ProjectRemovalResult.FolderKept(failure)
        else -> ProjectRemovalResult.Removed(trashed = askedForFolder)
    }
}

/** Returns null on success, or the reason the folder is still on disk. */
private suspend fun moveFolderToTrash(path: String): String? {
    trashRefusal(path)?.let { return it }

    return withContext(Dispatchers.IO) {
        try {
            // Re-resolved inside the IO block rather than passed in: the refusal above was
            // checked when the dialog opened, and the folder can have gone since.
            val moved = Desktop.getDesktop().moveToTrash(File(path))
            if (moved) {
                logger.info(LogCategory.FILE, "Moved a project folder to the Trash", mapOf("path" to path))
                null
            } else {
                logger.warn(LogCategory.FILE, "The system refused to trash a project folder", mapOf("path" to path))
                "The system would not move that folder to the Trash."
            }
        } catch (e: SecurityException) {
            logger.warn(LogCategory.FILE, "Not permitted to trash a project folder", mapOf("path" to path), error = e)
            "BOSS is not permitted to move that folder."
        } catch (e: IllegalArgumentException) {
            // What moveToTrash throws for a path that has gone between the check and here.
            logger.warn(
                LogCategory.FILE,
                "A project folder vanished before it could be trashed",
                mapOf("path" to path),
                error = e,
            )
            "That folder is already gone."
        } catch (e: UnsupportedOperationException) {
            logger.warn(LogCategory.FILE, "This platform cannot trash files", mapOf("path" to path), error = e)
            NO_TRASH
        }
    }
}

/**
 * Whether this desktop can move a file to the Trash at all.
 *
 * `Desktop.getDesktop()` throws in a headless JVM, which is how the test JVM runs, so the
 * headless check has to come first rather than being wrapped in a try.
 */
private const val NO_TRASH = "This system has no Trash that BOSS can move folders to."

private fun isTrashAvailable(): Boolean =
    Desktop.isDesktopSupported() &&
        Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)

/** What to tell the user. Pure, so the wording is pinned by a test rather than by reading it. */
fun ProjectRemovalResult.describe(project: Project): String =
    when (this) {
        is ProjectRemovalResult.Removed -> {
            if (trashed) {
                "Removed ${project.name} and moved its folder to the Trash"
            } else {
                "Removed ${project.name} from BOSS"
            }
        }

        // Both halves, in that order: the entry did go, and the folder did not. Reporting
        // only the failure would read as though nothing happened.
        is ProjectRemovalResult.FolderKept -> {
            "Removed ${project.name} from BOSS, but the folder is still there. $reason"
        }
    }

/**
 * [removeProject] plus the toast, so the two mount points of the remove dialog - the home
 * screen's project cards and the top bar's project menu - cannot report it differently.
 */
suspend fun removeProjectAndReport(
    project: Project,
    scope: ProjectRemovalScope,
) {
    val result = removeProject(project, scope)
    StatusMessageManager.showMessage(result.describe(project))
}
