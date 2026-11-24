package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossAlertDialog
import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.project.ProjectRemovalScope
import ai.rever.boss.project.trashRefusal
import ai.rever.boss.window.Project
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Checkbox
import androidx.compose.material.CheckboxDefaults
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Keeps the dialog from collapsing to the width of a short project name. */
private val DIALOG_MIN_WIDTH = 380.dp

/**
 * Asks what "remove this project" should mean before doing it.
 *
 * The cross on a project card used to call `removeRecentProject` straight away. That is
 * the right *default* - it forgets an entry and touches nothing - but it left no way to
 * get rid of a project properly, so the folder stayed behind on disk with no affordance
 * for it anywhere in BOSS.
 *
 * So: one confirm, two scopes. Forgetting is the unchecked default and is what the button
 * does if someone just presses through. The folder deletion is an explicit opt-in that
 * restates itself in the button label and turns it red, because it is the only thing BOSS
 * does to a directory the user has not otherwise asked it to touch.
 *
 * It moves the folder to the Trash - never an unlink - so a wrong click is recoverable
 * outside BOSS. [trashRefusal] decides whether that is possible at all and says why not;
 * an unavailable Trash disables the checkbox rather than quietly downgrading to a delete.
 */
@Composable
fun RemoveProjectDialog(
    project: Project,
    isOpenHere: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (ProjectRemovalScope) -> Unit,
) {
    // Read once per dialog: the answer depends on the filesystem, and re-reading it on
    // every recomposition would stat the disk from the composition thread.
    val refusal = remember(project.path) { trashRefusal(project.path) }
    var alsoTrash by remember(project.path) { mutableStateOf(false) }
    val canTrash = refusal == null

    BossAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove ${project.name}?") },
        text = {
            DialogBody(
                project = project,
                isOpenHere = isOpenHere,
                refusal = refusal,
                alsoTrash = alsoTrash && canTrash,
                canTrash = canTrash,
                onAlsoTrashChange = { alsoTrash = it },
            )
        },
        confirmButton = {
            val trashing = alsoTrash && canTrash
            TextButton(
                onClick = {
                    onConfirm(
                        if (trashing) ProjectRemovalScope.RECENTS_AND_FOLDER else ProjectRemovalScope.RECENTS_ONLY,
                    )
                },
            ) {
                // The label restates the destructive half rather than staying "Remove",
                // so the button and the checkbox cannot disagree about what is about to
                // happen.
                Text(
                    text = if (trashing) "Remove and Trash Folder" else "Remove",
                    color = if (trashing) BossTheme.colors.alert else BossTheme.colors.signalText,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = BossTheme.colors.textMuted)
            }
        },
    )
}

@Composable
private fun DialogBody(
    project: Project,
    isOpenHere: Boolean,
    refusal: String?,
    alsoTrash: Boolean,
    canTrash: Boolean,
    onAlsoTrashChange: (Boolean) -> Unit,
) {
    Column(
        modifier = Modifier.widthIn(min = DIALOG_MIN_WIDTH),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text =
                "This takes it off your recent projects. " +
                    "The folder stays where it is unless you say otherwise.",
            color = BossTheme.colors.textSecondary,
            fontSize = 12.sp,
        )

        Text(
            text = project.path,
            color = BossTheme.colors.textMuted,
            fontSize = 11.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        TrashOption(
            checked = alsoTrash,
            enabled = canTrash,
            onCheckedChange = onAlsoTrashChange,
        )

        refusal?.let {
            Text(text = it, color = BossTheme.colors.textMuted, fontSize = 11.sp)
        }

        if (isOpenHere) {
            // Worth saying, because it is the one case where the button does less than it
            // looks like it does: the window keeps whatever tabs the project opened.
            Text(
                text =
                    "This project is open in this window. " +
                        "Removing it does not close what is already open.",
                color = BossTheme.colors.warn,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun TrashOption(
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = BossTheme.colors.alert),
        )
        Text(
            text = "Also move the folder to the Trash",
            color = if (enabled) BossTheme.colors.textPrimary else BossTheme.colors.textMuted,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}
