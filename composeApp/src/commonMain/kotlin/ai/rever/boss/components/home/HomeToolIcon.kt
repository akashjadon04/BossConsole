package ai.rever.boss.components.home

import androidx.compose.ui.graphics.vector.ImageVector

/**
 * How a tool tile draws itself.
 *
 * Two cases, because the two populations in the grid have genuinely different icon sources and
 * neither can serve the other:
 *
 *  - An **installed** tool is registered, so it supplies its own `ImageVector` - a tab type's
 *    `icon`, or a panel's `SidebarItem.icon`. That is the icon the user already sees in the tab
 *    bar or the sidebar rail, so it is the only right answer.
 *  - A **not-installed** plugin has registered nothing. All the host holds is a store row, so the
 *    icon has to come from the store: [FromStore] carries `icon_url`.
 *
 * There is deliberately no third case mapping a plugin id to a built-in icon. That was the first
 * implementation and it was wrong: a hardcoded id-to-icon table has to be edited every time a
 * plugin ships, so it is stale by construction, and it silently gives the same generic glyph to
 * every plugin published after the build - including every third-party one.
 */
sealed interface HomeToolIcon {
    /** A registered tool's own icon. */
    data class Vector(
        val image: ImageVector,
    ) : HomeToolIcon

    /**
     * A store row's icon, addressed by URL, with a text fallback.
     *
     * [iconUrl] is `plugins.icon_url`. It is frequently blank - at the time of writing it is
     * blank for every row in the store - so [initials] is not an error path but the common one,
     * and it is derived from the plugin's own display name rather than looked up anywhere. That
     * keeps a tile distinguishable per plugin with no table to maintain, and it starts showing
     * real icons the moment the column is populated, with no client release.
     */
    data class FromStore(
        val iconUrl: String,
        val initials: String,
    ) : HomeToolIcon
}

/**
 * One or two letters standing in for a plugin with no icon.
 *
 * Takes the initial of each of the first two words, so "Tool Evolver" reads "TE" and "Arcade"
 * reads "A". Falls back to the first character of anything unsplittable, and to "?" for a blank
 * name, so this never returns an empty string for a tile to render as a hole.
 */
fun initialsFor(displayName: String): String {
    val words =
        displayName
            .split(' ', '-', '_', '.')
            .filter { it.isNotBlank() }
    return when {
        words.isEmpty() -> "?"
        words.size == 1 -> words[0].take(1).uppercase()
        else -> (words[0].take(1) + words[1].take(1)).uppercase()
    }
}
