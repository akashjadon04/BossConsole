package ai.rever.boss.components.home

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.window.LocalWindowGitState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Below this width the search affordance drops under the greeting instead of sitting beside it.
 *
 * A named constant with a pure predicate ([showsInlineSearch]) rather than an inline comparison,
 * so the breakpoint is pinned by a test with no display - the shape `AuthScaffold.showsBrandPanel`
 * and `BossDialog.shouldRouteHeavyweight` established. The old header made the same decision with
 * a bare `if (maxWidth < HeaderCompactWidth)` buried in the composable, which nothing could test.
 */
internal val HeaderInlineSearchMinWidth: Dp = 640.dp

internal fun showsInlineSearch(availableWidth: Dp): Boolean = availableWidth >= HeaderInlineSearchMinWidth

/** Width the search affordance owns when it sits beside the greeting. */
private val SearchWidth: Dp = 260.dp

/**
 * The home screen's header: who and where, then a way to find anything.
 *
 * **Replaces a session timer, "Files Today" and "Pages Today".** Those recomposed every second
 * to report how long the app had been open, and the file counter was fed by a path the home
 * screen's own file cards did not take, so it under-reported the one action most likely to have
 * caused it. What a person opening this screen needs to know is which project they are in, which
 * branch that is on, and how to get somewhere - so that is what it says.
 */
@Composable
internal fun HomeHeader(
    projectName: String,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = BossTheme.space

    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val inline = showsInlineSearch(maxWidth)
        if (inline) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Greeting(
                    projectName = projectName,
                    modifier = Modifier.weight(1f).padding(end = space.xl),
                )
                // The cap comes first and the fill takes what the cap allows, per the house
                // rule gated by DialogCardWidthConventionTest.
                SearchAffordance(
                    onClick = onSearch,
                    modifier = Modifier.width(SearchWidth),
                )
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(space.lg)) {
                Greeting(projectName = projectName)
                SearchAffordance(
                    onClick = onSearch,
                    modifier = Modifier.widthIn(max = SearchWidth).fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Greeting(
    projectName: String,
    modifier: Modifier = Modifier,
) {
    val colors = BossTheme.colors
    val space = BossTheme.space
    val gitState = LocalWindowGitState.current
    val isGitRepository by gitState?.isGitRepository?.collectAsState() ?: remember { mutableStateOf(false) }
    val branch by gitState?.currentBranch?.collectAsState() ?: remember { mutableStateOf<String?>(null) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(space.xs)) {
        Text(
            text = "BOSS CONSOLE",
            color = colors.signalText,
            style = BossTheme.type.label,
        )
        Text(
            text = "What would you like to work on?",
            color = colors.textPrimary,
            style = BossTheme.type.displaySmall,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(space.sm),
        ) {
            Text(
                text = projectName,
                color = colors.textSecondary,
                style = BossTheme.type.body,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Only when there is a repository. "detached" or "unknown" on a plain directory
            // would be noise about something the user did not ask about.
            if (isGitRepository && branch != null) {
                Dot()
                Icon(
                    imageVector = Icons.Outlined.AccountTree,
                    contentDescription = null,
                    tint = colors.textMuted,
                    modifier = Modifier.size(12.dp),
                )
                Text(
                    text = branch.orEmpty(),
                    color = colors.data,
                    style = BossTheme.type.data,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Dot() {
    Box(
        modifier =
            Modifier
                .size(3.dp)
                .background(BossTheme.colors.textMuted, CircleShape),
    )
}

/**
 * Opens the existing global search rather than growing a second search of its own.
 *
 * A button shaped like a field, not a real text field: the search that already exists
 * (`GlobalSearchDialog`, bound to `search.open`) indexes files, tabs, bookmarks, run configs and
 * commands, and a field here would either duplicate that or be a worse version of it.
 */
@Composable
private fun SearchAffordance(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = BossTheme.colors
    val space = BossTheme.space

    Row(
        modifier =
            modifier
                .background(colors.raised, BossTheme.radius.inputShape)
                .clickable { onClick() }
                .padding(horizontal = space.md, vertical = space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(space.sm),
    ) {
        Icon(
            imageVector = Icons.Outlined.Search,
            contentDescription = null,
            tint = colors.textMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = "Search everything",
            color = colors.textMuted,
            style = BossTheme.type.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
