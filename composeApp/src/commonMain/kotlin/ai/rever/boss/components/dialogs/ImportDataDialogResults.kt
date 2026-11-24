package ai.rever.boss.components.dialogs

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.services.importer.ImportPreview
import ai.rever.boss.services.importer.ImportResult
import ai.rever.boss.services.importer.SkipReason
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Review / progress / results stages for ImportDataDialog. Split from the
// source-picking stages so neither file carries the whole flow.

@Composable
internal fun ReviewContent(
    preview: ImportPreview,
    canImportPasswords: Boolean,
    bookmarksAvailable: Boolean,
    slowBookmarkPath: Boolean,
    sourceNote: String?,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    val passwordsBlocked = preview.passwords.isNotEmpty() && !canImportPasswords
    val bookmarksBlocked = preview.bookmarks.isNotEmpty() && !bookmarksAvailable
    val anythingToDo =
        (preview.passwords.isNotEmpty() && canImportPasswords) ||
            (preview.bookmarks.isNotEmpty() && bookmarksAvailable)

    Column {
        // Explains a partial read — e.g. bookmarks came through but the
        // keychain prompt was declined, so passwords did not.
        sourceNote?.let { note ->
            Notice(note)
            Spacer(Modifier.height(BossTheme.space.sm))
        }

        HalfSummary(
            count = preview.passwords.size,
            label = "passwords",
            enabled = canImportPasswords,
            blockedNotice =
                "Sign in to BOSS to import passwords into your encrypted vault."
                    .takeIf { passwordsBlocked },
        )
        HalfSummary(
            count = preview.bookmarks.size,
            label = "bookmarks",
            enabled = bookmarksAvailable,
            blockedNotice =
                when {
                    bookmarksBlocked -> {
                        "The Bookmarks tool isn't available, so bookmarks can't be imported."
                    }

                    slowBookmarkPath -> {
                        "Your Bookmarks tool predates bulk import, so this will be slower. " +
                            "Updating it first is recommended."
                    }

                    else -> {
                        null
                    }
                },
        )

        SkippedRows(preview)

        DialogActions(
            confirmText = "Import",
            onConfirm = onStart,
            onCancel = onCancel,
            confirmEnabled = anythingToDo,
        )
    }
}

/** The "these rows won't import" list, with reasons. */
@Composable
private fun SkippedRows(preview: ImportPreview) {
    if (preview.skipped.isEmpty()) return

    Spacer(Modifier.height(BossTheme.space.md))
    Text(
        "${preview.skipped.size} rows will be skipped",
        style = BossTheme.type.body,
        color = BossTheme.colors.textSecondary,
    )
    Column(Modifier.heightIn(max = SCROLL_LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
        // Capped: a bad export can skip thousands, and the dialog only needs
        // to show enough for the user to recognise the pattern.
        preview.skipped.take(MAX_LISTED).forEach { row ->
            Text(
                "Row ${row.rowNumber}: ${row.reason.describe()} - ${row.label}",
                style = BossTheme.type.micro,
                color = BossTheme.colors.textMuted,
            )
        }
        MoreRow(preview.skipped.size - MAX_LISTED)
    }
}

/** One half of the preview: how many, whether it can run, and why not. */
@Composable
private fun HalfSummary(
    count: Int,
    label: String,
    enabled: Boolean,
    blockedNotice: String?,
) {
    if (count == 0) return
    SummaryRow("$count $label", enabled = enabled)
    blockedNotice?.let { Notice(it) }
}

@Composable
private fun SummaryRow(
    label: String,
    enabled: Boolean,
) {
    Text(
        label,
        style = BossTheme.type.data,
        color = if (enabled) BossTheme.colors.textPrimary else BossTheme.colors.textMuted,
        modifier = Modifier.padding(vertical = BossTheme.space.hairline),
    )
}

@Composable
private fun Notice(message: String) {
    Text(
        message,
        style = BossTheme.type.body,
        color = BossTheme.colors.warn,
        modifier = Modifier.padding(bottom = BossTheme.space.xs),
    )
}

@Composable
internal fun RunningContent(
    done: Int,
    total: Int,
    onCancel: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (total > 0) {
            LinearProgressIndicator(
                progress = done.toFloat() / total,
                modifier = Modifier.fillMaxWidth(),
                color = BossTheme.colors.signal,
            )
            Spacer(Modifier.height(BossTheme.space.md))
            Text("$done of $total", style = BossTheme.type.body, color = BossTheme.colors.textSecondary)
        } else {
            CircularProgressIndicator(color = BossTheme.colors.signal)
        }
        Spacer(Modifier.height(BossTheme.space.lg))
        TextButton(onClick = onCancel) { Text("Cancel", color = BossTheme.colors.textSecondary) }
    }
}

@Composable
internal fun FinishedContent(
    passwords: ImportResult?,
    bookmarks: ImportResult?,
    cancelled: Boolean,
    onClose: () -> Unit,
) {
    Column {
        if (cancelled) {
            Notice(
                if (passwords == null && bookmarks == null) {
                    "Import cancelled before anything was written."
                } else {
                    "Import cancelled. Whatever is listed below was already saved and stays saved."
                },
            )
            Spacer(Modifier.height(BossTheme.space.sm))
        }
        passwords?.let { ResultBlock("Passwords", it) }
        bookmarks?.let { ResultBlock("Bookmarks", it) }

        Spacer(Modifier.height(BossTheme.space.lg))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onClose) { Text("Done", color = BossTheme.colors.signalText) }
        }
    }
}

@Composable
private fun ResultBlock(
    title: String,
    result: ImportResult,
) {
    Column(Modifier.padding(bottom = BossTheme.space.sm)) {
        Text(title, style = BossTheme.type.title, color = BossTheme.colors.textPrimary)
        Text(
            "${result.imported} imported · ${result.skipped.size} skipped · ${result.failed} failed",
            style = BossTheme.type.body,
            color = BossTheme.colors.textSecondary,
        )
        if (result.failures.isNotEmpty()) {
            Column(Modifier.heightIn(max = SCROLL_LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
                result.failures.take(20).forEach { failure ->
                    Text(failure, style = BossTheme.type.micro, color = BossTheme.colors.alert)
                }
            }
        }
    }
}

private fun SkipReason.describe(): String =
    when (this) {
        SkipReason.MISSING_URL -> "no website"
        SkipReason.MISSING_USERNAME -> "no username"
        SkipReason.MISSING_PASSWORD -> "no password"
        SkipReason.MALFORMED_ROW -> "malformed row"
        SkipReason.ALREADY_EXISTS -> "already saved"
    }

/** Caps the skipped/failure lists so the action row stays reachable. */
private val SCROLL_LIST_MAX_HEIGHT = 120.dp

/** Says how many rows the list left out, rather than truncating silently. */
@Composable
private fun MoreRow(remaining: Int) {
    if (remaining <= 0) return
    Text(
        "and $remaining more",
        style = BossTheme.type.micro,
        color = BossTheme.colors.textMuted,
    )
}

/** Rows shown before the list is summarised. */
private const val MAX_LISTED = 50
