package ai.rever.boss.components.settings.search

import ai.rever.boss.components.settings.shared.SettingsTheme.AccentColor
import ai.rever.boss.components.settings.shared.SettingsTheme.TextMuted
import ai.rever.boss.components.settings.shared.SettingsTheme.TextPrimary
import ai.rever.boss.plugin.ui.BossThemeController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The nav rail's contents while a query is present.
 *
 * A flat list rather than a filtered section rail, because the useful answer is usually a control
 * rather than a page: "user agent" should offer Browser Identity, not offer Browser and leave the
 * reader to find it. Each row carries the breadcrumb underneath, which is the only thing that tells
 * the two "Warning Threshold" rows in Performance apart.
 */
@Composable
internal fun SettingsSearchResults(
    hits: List<SettingsSearchHit>,
    selectedIndex: Int,
    onPick: (SettingsSearchHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hits.isEmpty()) {
        Text(
            text = "No matching settings",
            color = TextMuted,
            fontSize = 12.sp,
            modifier = modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
        return
    }

    val listState = rememberLazyListState()

    // Keep the keyboard selection on screen. Arrowing past the fold otherwise moves a highlight
    // the user cannot see, and Enter then opens something they never chose.
    LaunchedEffect(selectedIndex) {
        if (selectedIndex in hits.indices) listState.animateScrollToItem(selectedIndex)
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        itemsIndexed(hits, key = { _, hit -> hit.entry.resultKey }) { index, hit ->
            ResultRow(
                hit = hit,
                isSelected = index == selectedIndex,
                onClick = { onPick(hit) },
            )
        }
    }
}

@Composable
private fun ResultRow(
    hit: SettingsSearchHit,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(if (isSelected) AccentColor.copy(alpha = 0.15f) else Color.Transparent)
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = highlighted(hit),
            color = if (isSelected) AccentColor else TextPrimary,
            fontSize = 13.sp,
            // Two lines, because the rail is 180dp and "Enable Performance Monitoring" does not fit
            // on one. Truncating to one line would make several rows read as the same setting.
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = hit.entry.breadcrumb,
            color = TextMuted,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The label with the matched characters emphasised, the way the global search dialog does it. */
@Composable
private fun highlighted(hit: SettingsSearchHit): AnnotatedString {
    val label = hit.entry.label
    val ranges = hit.labelMatches.filter { it.start in label.indices && it.end <= label.length }
    if (ranges.isEmpty()) return AnnotatedString(label)

    // signalText rather than signal: this is a glyph, and signal is only held to a fill's contrast
    // floor. On Blueprint the two differ enough for it to matter.
    val emphasis = SpanStyle(color = BossThemeController.current.colors.signalText, fontWeight = FontWeight.SemiBold)
    return buildAnnotatedString {
        var cursor = 0
        for (range in ranges.sortedBy { it.start }) {
            if (range.start > cursor) append(label.substring(cursor, range.start))
            if (range.start >= cursor) {
                withStyle(emphasis) { append(label.substring(range.start, range.end)) }
                cursor = range.end
            }
        }
        if (cursor < label.length) append(label.substring(cursor))
    }
}
