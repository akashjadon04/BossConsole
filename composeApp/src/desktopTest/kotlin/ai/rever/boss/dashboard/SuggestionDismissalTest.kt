package ai.rever.boss.dashboard

import ai.rever.boss.plugin.browser.canonicalUrlKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The suggested-site cards on the home screen can be dismissed, and stay dismissed.
 *
 * `getSuggestions` pads its list from a hardcoded `POPULAR_DEV_SITES` of seventeen entries. Those
 * live in no persisted list, so the card's X called `removePage`, which filtered only the recorded
 * pages, saved an unchanged list, and the card re-rendered on the next frame. "Clear" could not
 * remove them either - and once the recorded pages were empty it hid its own label, so the
 * seventeen promo cards were left with no affordance at all.
 *
 * Driven through the pure [rankSuggestions] rather than the manager object. Calling
 * `RecentBrowserPagesManager.clearAll()` from a test would mutate a process-wide singleton that
 * other tests read, and would persist to the developer's real
 * `~/.boss/recent-browser-pages.json`.
 */
class SuggestionDismissalTest {
    private val now = 1_700_000_000_000L

    private fun page(
        url: String,
        visits: Int = 1,
        lastVisited: Long = now,
    ) = RecentBrowserPage(url = url, title = url, visitCount = visits, lastVisited = lastVisited)

    private val popular =
        listOf(
            page("https://promo-one.example", visits = 0, lastVisited = 0),
            page("https://promo-two.example", visits = 0, lastVisited = 0),
        )

    private fun rank(
        recent: List<RecentBrowserPage> = emptyList(),
        dismissed: Set<String> = emptySet(),
        limit: Int = 20,
    ) = rankSuggestions(recent = recent, dismissed = dismissed, popular = popular, limit = limit, now = now)

    @Test
    fun `padding suggestions fill the list when there is no history`() {
        assertEquals(2, rank().size)
    }

    @Test
    fun `a dismissed padding suggestion does not come back`() {
        val dismissed = setOf(canonicalUrlKey("https://promo-one.example"))

        val suggestions = rank(dismissed = dismissed)

        assertFalse(
            suggestions.any { it.url == "https://promo-one.example" },
            "A dismissed suggestion came back. Before the fix its X filtered a list the card was " +
                "never in, so it re-rendered immediately.",
        )
        assertEquals(1, suggestions.size)
    }

    @Test
    fun `dismissing every padding suggestion empties the strip`() {
        // What "Clear" now does, and what it failed to do: emptying only the recorded pages left
        // all seventeen promos on screen.
        val dismissed = popular.map { canonicalUrlKey(it.url) }.toSet()

        assertTrue(rank(dismissed = dismissed).isEmpty())
    }

    @Test
    fun `dismissal is matched canonically, not by string equality`() {
        // The promo list and a recorded visit can spell the same page differently, so a trailing
        // slash or a fragment must not resurrect a dismissed card.
        val dismissed = setOf(canonicalUrlKey("https://promo-one.example/#section"))

        assertFalse(rank(dismissed = dismissed).any { it.url == "https://promo-one.example" })
    }

    @Test
    fun `history is never displaced by padding`() {
        val recent = List(20) { page("https://real-$it.example", visits = it + 1) }

        val suggestions = rank(recent = recent)

        assertTrue(
            suggestions.none { it.url.contains("promo") },
            "Padding must only fill leftover slots, never take one from real history",
        )
    }

    @Test
    fun `a recorded page is excluded from padding without needing a dismissal`() {
        val recent = listOf(page("https://promo-one.example", visits = 5))

        val suggestions = rank(recent = recent)

        assertEquals(2, suggestions.size)
        assertEquals(1, suggestions.count { it.url == "https://promo-one.example" })
    }

    @Test
    fun `the dismissed set is part of the persisted model and defaults to empty`() {
        // The field is what makes a dismissal outlive the session; without it the promo returns
        // on the next launch and the user dismisses it again, forever.
        val data = RecentBrowserPagesData(dismissedSuggestions = listOf("https://example.com"))

        assertTrue(data.dismissedSuggestions.contains("https://example.com"))
        // Absent decodes back to empty, which is what `encodeDefaults = false` relies on.
        assertTrue(RecentBrowserPagesData().dismissedSuggestions.isEmpty())
    }
}
