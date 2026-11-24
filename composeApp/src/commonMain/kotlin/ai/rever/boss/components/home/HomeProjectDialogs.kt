package ai.rever.boss.components.home

import ai.rever.boss.components.dialogs.ProjectOpenModeDialog
import ai.rever.boss.components.dialogs.RemoveProjectDialog
import ai.rever.boss.project.ProjectRemovalScope
import ai.rever.boss.window.Project
import ai.rever.boss.window.WindowOperations
import androidx.compose.runtime.Composable

/**
 * The two dialogs a project card can raise, kept out of [HomeScreen] so its body stays a
 * readable list of sections.
 */
@Composable
internal fun HomeProjectDialogs(
    projectToOpen: Project?,
    projectToRemove: Project?,
    openProjectPath: String,
    onOpenHere: (Project) -> Unit,
    onOpenDone: () -> Unit,
    onRemoveDone: () -> Unit,
    onRemove: (Project, ProjectRemovalScope) -> Unit,
) {
    projectToOpen?.let { project ->
        OpenModeDialog(project = project, onOpenHere = onOpenHere, onDone = onOpenDone)
    }

    projectToRemove?.let { project ->
        RemoveProjectDialog(
            project = project,
            isOpenHere = project.path == openProjectPath,
            onDismiss = onRemoveDone,
            onConfirm = { removalScope ->
                onRemoveDone()
                onRemove(project, removalScope)
            },
        )
    }
}

/**
 * Asks which window a recent project should open in.
 *
 * Extracted mostly to keep [HomeScreen] readable, but the new-window branch is the interesting
 * part: it must create the window *with* the project.
 */
@Composable
private fun OpenModeDialog(
    project: Project,
    onOpenHere: (Project) -> Unit,
    onDone: () -> Unit,
) {
    ProjectOpenModeDialog(
        project = project,
        onDismiss = onDone,
        onOpenInCurrentWindow = { chosen ->
            onOpenHere(chosen)
            onDone()
        },
        onOpenInNewWindow = { chosen ->
            // createNewWindowWithProject, not createNewWindow() then select. Project state is PER
            // WINDOW, so selecting after creating applied the project to the window the user
            // clicked in and left the new one empty. The two other callers of this flow
            // (BossTopBar, BossAppDialogs) already use this API.
            WindowOperations.createNewWindowWithProject(chosen)
            onDone()
        },
    )
}
