package ai.rever.boss.components.settings.search

import ai.rever.boss.components.settings.sidebar.SettingsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Ranking behaviour, against the real index rather than a fixture. */
class SettingsSearchMatcherTest {
    private fun search(query: String) = SettingsSearchMatcher.search(query, SettingsSearchIndex.builtIn)

    private fun labels(query: String) = search(query).map { it.entry.label }

    /**
     * The query this whole matcher exists for.
     *
     * `FuzzyMatcher` is a subsequence matcher over one string, so `match("user agent", "Browser
     * Identity")` is null and the obvious implementation finds nothing at all here. Both tokens land
     * on the *group* instead.
     */
    @Test
    fun `a multi-word query matches through the group name`() {
        assertTrue(
            "Browser Identity" in labels("user agent"),
            "searching the words a user would actually type found: ${labels("user agent")}",
        )
    }

    @Test
    fun `every token must match, so a second word narrows rather than widens`() {
        val one = search("threshold").size
        val two = search("threshold memory").size

        assertTrue(one > 0 && two > 0, "expected hits for both queries")
        assertTrue(two < one, "adding a word widened the result set ($one -> $two)")
    }

    /**
     * The collision, from the ranking side. Both rows are called "Warning Threshold"; the group is
     * the only thing that separates them, so it has to be what decides the order.
     */
    @Test
    fun `the group disambiguates duplicate labels`() {
        val hits = search("memory warning")
        val best = hits.firstOrNull()

        assertEquals("Warning Threshold", best?.entry?.label)
        assertEquals("Memory Thresholds", best?.entry?.group)
    }

    @Test
    fun `a keyword finds a setting whose label does not contain the word`() {
        val hits = labels("passkey")

        assertTrue(
            hits.any { it == "Platform Authenticator" || it == "WebAuthn Support" },
            "expected the WebAuthn rows via their keywords, got: $hits",
        )
    }

    /**
     * Keywords are damped, so an entry that only matches through a curated synonym cannot outrank
     * one whose actual label contains the word. "memory" is in the label of "Detected memory" and
     * of the "Memory Thresholds" group, and only in the *keywords* of "Max Heap per Plugin" and
     * "Use Lite below". Without [SettingsSearchMatcher]'s keyword penalty the short keyword-only
     * labels win, because FuzzyMatcher pays a large bonus for a short target.
     */
    @Test
    fun `a label match outranks a keyword-only match`() {
        val best = search("memory").first().entry

        assertTrue(
            best.label.contains("memory", ignoreCase = true),
            "a keyword-only hit outranked every real label match: '${best.label}' (${best.breadcrumb})",
        )
    }

    /**
     * Pins a ranking choice that is easy to read as a bug and is not one: searching the name of a
     * group returns that group's *control* above the group heading. "Resource Mode" is a heading;
     * "Mode" beneath it is the dropdown that actually changes the resource mode, so it is the
     * better answer even though the heading is the closer string match.
     */
    @Test
    fun `a group's control outranks the group heading`() {
        val best = search("resource mode").first().entry

        assertEquals("Mode", best.label)
        assertEquals("Resource Mode", best.group)
    }

    @Test
    fun `a delegated section is reachable by a keyword its page never shows the host`() {
        val hits = search("scrollback")

        assertEquals(SettingsSection.TERMINAL, hits.firstOrNull()?.entry?.section)
        assertTrue(
            hits
                .first()
                .entry.highlightable
                .not(),
            "the Terminal panel belongs to BossTerm, so this hit must not promise a highlight",
        )
    }

    /**
     * Regression: "tab switching" found nothing at all.
     *
     * Shortcuts had been filed with the delegated sections - panels owned by other modules - on the
     * grounds that it has its own search box. But its page is host code, so its controls were
     * indexable all along, and the mis-filing also exempted the section from the drift guard, which
     * is why no test caught it.
     */
    @Test
    fun `tab switching finds the Shortcuts controls`() {
        val hits = search("tab switching")

        assertTrue(hits.isNotEmpty(), "searching the words on screen found nothing")
        assertEquals(SettingsSection.KEYMAP, hits.first().entry.section)
        assertTrue(
            hits.map { it.entry.label }.containsAll(listOf("Positional", "Most recently used")),
            "expected both tab-switching modes, got: ${hits.map { it.entry.label }}",
        )
        // Without the context the row reads "Positional / Shortcuts", which says nothing about what
        // it does - the heading it sits under is a plain Text, so `group` cannot supply this.
        assertEquals(
            "Shortcuts > Tab switching",
            hits.first { it.entry.label == "Positional" }.entry.breadcrumb,
        )
    }

    @Test
    fun `a blank query returns nothing rather than everything`() {
        assertTrue(search("").isEmpty())
        assertTrue(search("   ").isEmpty())
    }

    @Test
    fun `a query that matches nothing returns nothing`() {
        assertTrue(search("zzzzqqqq").isEmpty())
    }

    @Test
    fun `results are capped`() {
        val hits = SettingsSearchMatcher.search("e", SettingsSearchIndex.builtIn, limit = 5)

        assertTrue(hits.size <= 5, "expected at most 5, got ${hits.size}")
    }

    /** Ties break on the label, so the rail does not reshuffle between identical queries. */
    @Test
    fun `ordering is deterministic`() {
        assertEquals(labels("threshold"), labels("threshold"))
    }

    @Test
    fun `plugin pages are searchable when merged in`() {
        val entries = SettingsSearchIndex.builtIn + pluginPageEntry("jupyter", "Jupyter", "Kernel settings")
        val hits = SettingsSearchMatcher.search("jupyter", entries)

        assertEquals("jupyter", hits.firstOrNull()?.entry?.pluginPageId)
    }
}
