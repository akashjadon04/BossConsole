package ai.rever.boss.components.workspaces

import ai.rever.boss.plugin.workspace.SplitConfig.HorizontalSplit
import ai.rever.boss.plugin.workspace.SplitConfig.SinglePanel
import ai.rever.boss.plugin.workspace.SplitConfig.VerticalSplit

/**
 * Placeholders that only mean something once a project is selected.
 *
 * `{projectPath}` falls back to `~/BossProjects` and `{gitRemoteUrl}` to google.com when
 * there is no project, so a workspace using them does not fail - it quietly does the wrong
 * thing. The Claude Code default, applied with no project, would open a terminal running
 * `claude --dangerously-skip-permissions` in a directory the user never chose. The fallback
 * moving out of the home directory (see `DefaultWorkingDirectory`) makes that less alarming,
 * not correct: an agent still has no project to work on.
 */
private val PROJECT_PLACEHOLDERS =
    listOf(
        "{projectPath}",
        "{gitRemoteUrl}",
        "{currentFile}",
        "{claudeContinueFlag}",
    )

/**
 * Whether this workspace only makes sense with a project selected.
 *
 * Used by the fresh-start apply: a window that restored nothing and has no project
 * applies the configured default only if the default can stand on its own. That keeps
 * the rule platform-neutral - browser-only comes up on a fresh Windows install because
 * it needs nothing, and the terminal-first defaults keep waiting for a project on every
 * platform exactly as they did before.
 */
fun LayoutWorkspace.requiresProject(): Boolean = layout.collectTabs().any { it.usesProjectPlaceholder() }

private fun TabConfig.usesProjectPlaceholder(): Boolean =
    listOfNotNull(url, filePath, initialCommand, workingDirectory)
        .any { field -> PROJECT_PLACEHOLDERS.any { it in field } }

private fun SplitConfig.collectTabs(): List<TabConfig> =
    when (this) {
        is SinglePanel -> panel.tabs
        is VerticalSplit -> left.collectTabs() + right.collectTabs()
        is HorizontalSplit -> top.collectTabs() + bottom.collectTabs()
    }
