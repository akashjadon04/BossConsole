package ai.rever.boss.components.plugin

import ai.rever.boss.plugin.api.PluginUnloadIntent
import ai.rever.boss.plugin.ui.BossDialog
import ai.rever.boss.plugin.ui.BossTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.Card
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties

/**
 * Asks whether the plugins that depend on one being updated or removed may be restarted.
 *
 * A dialog rather than the shared [ai.rever.boss.components.dialogs.ConfirmationDialog] because
 * the answer turns on *which* plugins those are: "3 plugins depend on this" is not something a
 * person can decide about, and the whole point of asking is that the update used to be refused
 * with the names known only to the host log.
 *
 * The list is scrollable and height-capped. There is no upper bound on how many plugins can
 * declare one dependency, and a dialog that grew past the window would put its buttons offscreen.
 *
 * @param prompt the plugin being unloaded, why, and everything that depends on it
 * @param onDismiss the user declined; the caller leaves the plugin loaded
 * @param onConfirm the user agreed; the caller forces the unload and restarts the dependents
 */
@Composable
fun DependentRestartDialog(
    prompt: DependentRestartPrompt,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    BossDialog(
        onDismissRequest = onDismiss,
        properties =
            DialogProperties(
                dismissOnBackPress = true,
                dismissOnClickOutside = true,
                usePlatformDefaultWidth = false,
            ),
    ) {
        Card(
            modifier =
                Modifier
                    .width(440.dp)
                    .onKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onDismiss()
                            true
                        } else {
                            false
                        }
                    },
            shape = RoundedCornerShape(8.dp),
            backgroundColor = BossTheme.colors.panel,
            elevation = 8.dp,
        ) {
            DependentRestartBody(prompt = prompt, onDismiss = onDismiss, onConfirm = onConfirm)
        }
    }
}

@Composable
private fun DependentRestartBody(
    prompt: DependentRestartPrompt,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(modifier = Modifier.padding(20.dp)) {
        Text(
            text = DependentRestartCopy.title(prompt.intent),
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = BossTheme.colors.textPrimary,
        )

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            // The target's display name comes from its manifest, so it is clamped for the same
            // reason MissingDependencyDialog clamps its own: a crafted name must not be able to
            // grow the dialog or run on into text that reads like our copy.
            text = DependentRestartCopy.message(prompt.intent, prompt.targetDisplayName, prompt.dependents),
            fontSize = 13.sp,
            color = BossTheme.colors.textSecondary,
            maxLines = 5,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(modifier = Modifier.height(14.dp))

        LazyColumn(
            modifier = Modifier.heightIn(max = 220.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(prompt.dependents, key = { dependent -> dependent.pluginId }) { dependent ->
                DependentRow(
                    dependent = dependent,
                    openInstances = prompt.openInstancesOf(dependent),
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(modifier = Modifier.weight(1f))

            TextButton(
                onClick = onDismiss,
                colors = ButtonDefaults.textButtonColors(contentColor = BossTheme.colors.textSecondary),
            ) {
                Text("Cancel")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = onConfirm,
                colors =
                    ButtonDefaults.buttonColors(
                        // Removing is the destructive one: a dependent loses what it needs and no
                        // newer version arrives to replace it. Updating restarts things that come
                        // straight back, so it gets the ordinary signal colour.
                        backgroundColor =
                            if (prompt.intent == PluginUnloadIntent.REMOVE) {
                                BossTheme.colors.alert
                            } else {
                                BossTheme.colors.signal
                            },
                        contentColor = BossTheme.colors.onSignal,
                    ),
            ) {
                Text(DependentRestartCopy.confirmLabel(prompt.intent))
            }
        }
    }
}

@Composable
private fun DependentRow(
    dependent: DependentPlugin,
    openInstances: Int,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = dependent.displayName,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = BossTheme.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                // Optional dependents are in this list too, and saying so is what stops the
                // dialog reading as a warning about plugins that are all about to break.
                text = if (dependent.optional) "Optional" else "Required",
                fontSize = 11.sp,
                color = BossTheme.colors.textMuted,
            )
        }
        Text(
            // What confirming costs, in the only unit the user has: tabs they have open. Silent
            // when nothing is open, so the common case does not carry a "0 open tabs" line.
            text =
                if (openInstances > 0) {
                    val tabs = if (openInstances == 1) "tab" else "tabs"
                    "${dependent.pluginId} - $openInstances open $tabs will close"
                } else {
                    dependent.pluginId
                },
            fontSize = 11.sp,
            color = BossTheme.colors.textMuted,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
