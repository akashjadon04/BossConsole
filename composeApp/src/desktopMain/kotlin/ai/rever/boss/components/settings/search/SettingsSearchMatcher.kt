package ai.rever.boss.components.settings.search

import ai.rever.boss.search.FuzzyMatcher
import ai.rever.boss.search.MatchRange

/** A matched entry, with the ranges inside [SettingsSearchEntry.label] that the query hit. */
data class SettingsSearchHit(
    val entry: SettingsSearchEntry,
    val score: Int,
    val labelMatches: List<MatchRange>,
)

/**
 * Ranks [SettingsSearchEntry] against a typed query.
 *
 * Built on [FuzzyMatcher] - the same scorer the global search dialog uses - but it cannot simply
 * hand the raw query over. `FuzzyMatcher.match` is a strict *subsequence* matcher against a single
 * target, so the obvious call `match("user agent", "Browser Identity")` returns null and the most
 * natural way to look for that setting finds nothing. Two things fix it:
 *
 *  1. **Tokenise the query.** Each whitespace-separated token is matched independently, and every
 *     token must land somewhere on the entry. So "user agent" succeeds because "user" and "agent"
 *     both hit the *group* ("User Agent") even though neither is in the label.
 *  2. **Match each token against several fields**, taking that token's best field. An entry is more
 *     than its label: the group and section names are how a user describes where a setting lives.
 *
 * Keyword hits are damped ([KEYWORD_PENALTY]) so a curated synonym never outranks a real label
 * match. Without it "Mode" - which carries the keyword "resource" - would beat "Resource Mode"
 * itself on a search for "resource".
 */
object SettingsSearchMatcher {
    /** Keyword and section-name hits are worth less than a hit on the label or its group. */
    private const val KEYWORD_PENALTY = 40
    private const val SECTION_PENALTY = 25

    /** A hit on the label proper is what the user is most likely aiming at. */
    private const val LABEL_BONUS = 30

    /** Results shown in the 180dp rail. Beyond this the list is just noise. */
    const val DEFAULT_LIMIT = 40

    /**
     * Rank [entries] against [query], best first.
     *
     * A blank query returns nothing rather than everything: the caller reads an empty list as "show
     * the normal nav rail", and returning the whole index there would make every section render as
     * a search result the moment the window opened.
     */
    fun search(
        query: String,
        entries: List<SettingsSearchEntry>,
        limit: Int = DEFAULT_LIMIT,
    ): List<SettingsSearchHit> {
        val tokens = query.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return emptyList()

        return entries
            .mapNotNull { entry -> hitFor(entry, tokens) }
            .sortedWith(compareByDescending<SettingsSearchHit> { it.score }.thenBy { it.entry.label })
            .take(limit)
    }

    /** Null unless *every* token matched some field: a query is a conjunction, not a suggestion. */
    private fun hitFor(
        entry: SettingsSearchEntry,
        tokens: List<String>,
    ): SettingsSearchHit? {
        var total = 0
        var labelMatches = emptyList<MatchRange>()

        for (token in tokens) {
            val onLabel = FuzzyMatcher.match(token, entry.label)
            var best = onLabel?.let { it.score + LABEL_BONUS }
            if (onLabel != null && labelMatches.isEmpty()) labelMatches = onLabel.matchRanges

            entry.group?.let { group ->
                FuzzyMatcher.match(token, group)?.let { best = maxOfNullable(best, it.score) }
            }
            entry.section?.let { section ->
                FuzzyMatcher.match(token, section.displayName)?.let {
                    best = maxOfNullable(best, it.score - SECTION_PENALTY)
                }
            }
            for (keyword in entry.keywords) {
                FuzzyMatcher.match(token, keyword)?.let {
                    best = maxOfNullable(best, it.score - KEYWORD_PENALTY)
                }
            }

            // One unmatched token disqualifies the entry. Narrowing is the whole point of typing
            // a second word, so an entry that ignores it would make the list grow as the user
            // types - which is the opposite of what every search box in the app does.
            total += best ?: return null
        }

        return SettingsSearchHit(entry = entry, score = total, labelMatches = labelMatches)
    }

    private fun maxOfNullable(
        current: Int?,
        candidate: Int,
    ): Int = if (current == null) candidate else maxOf(current, candidate)

    private val WHITESPACE = Regex("\\s+")
}
