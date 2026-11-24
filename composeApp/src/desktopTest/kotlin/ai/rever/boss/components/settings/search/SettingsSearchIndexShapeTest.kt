package ai.rever.boss.components.settings.search

import ai.rever.boss.components.bars.ChromeBar
import ai.rever.boss.components.bars.displayName
import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.settings.sidebar.SettingsSection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/** Properties of the index that hold regardless of what the sources currently say. */
class SettingsSearchIndexShapeTest {
    /**
     * The guard the whole (section, group, label) identity exists for.
     *
     * Performance declares "Warning Threshold" and "Critical Threshold" under both "Memory
     * Thresholds" and "CPU Thresholds", and Browser Engine declares "Rendering mode" under both
     * "Rendering" and "Effective Chromium command line". Had any of those been written without its
     * group, two entries would collapse to one key here - and in the window, one search result
     * would scroll to one row while lighting up two.
     */
    @Test
    fun `no two entries share a result key`() {
        val duplicates =
            SettingsSearchIndex.builtIn
                .groupBy { it.resultKey }
                .filterValues { it.size > 1 }

        if (duplicates.isNotEmpty()) {
            fail(
                "These search entries collide on (section, group, label), so they cannot be told " +
                    "apart when navigating or highlighting: ${duplicates.keys.sorted()}",
            )
        }
    }

    /** A new section must not be silently unsearchable. Sections with no controls still get one entry. */
    @Test
    fun `every section appears in the index`() {
        val covered = SettingsSearchIndex.builtIn.mapNotNull { it.section }.toSet()
        val missing = SettingsSection.entries.toSet() - covered

        assertTrue(
            missing.isEmpty(),
            "These settings sections cannot be found by search at all: ${missing.map { it.name }.sorted()}. " +
                "Add entries for their controls, or a `delegated(...)` line if the host does not own the page.",
        )
    }

    /**
     * Recomputes the templated family rather than restating it.
     *
     * `WindowAppearanceSettings` builds these labels with `"Show ${bar.displayName()}"` over
     * `ChromeBar.entries`, so the index holds four literals that no source file contains verbatim -
     * which is why the drift test has to exempt them. Adding a fifth bar has to fail somewhere, and
     * this is that somewhere.
     */
    @Test
    fun `the Bars group lists exactly one entry per ChromeBar`() {
        val expected = ChromeBar.entries.map { "Show ${it.displayName()}" }.sorted()
        val actual =
            SettingsSearchIndex.builtIn
                .filter { it.section == SettingsSection.WINDOW_APPEARANCE && it.group == "Bars" }
                .map { it.label }
                .filter { it.startsWith("Show ") }
                .sorted()

        assertEquals(expected, actual, "the indexed Bars toggles have drifted from ChromeBar.entries")
    }

    /**
     * The four sections whose pages belong to other modules own no host controls, so they must stay
     * section-level. A hand-added BossTerm label here would produce a result that navigates to the
     * Terminal page and then highlights nothing, because no host control carries it.
     */
    @Test
    fun `delegated sections carry a single non-highlightable entry`() {
        val delegated =
            setOf(
                SettingsSection.TERMINAL,
                SettingsSection.BOSS_EDITOR,
                SettingsSection.LANGUAGE_SERVERS,
            )

        delegated.forEach { section ->
            val entries = SettingsSearchIndex.builtIn.filter { it.section == section }
            assertEquals(1, entries.size, "${section.name} should have exactly one section-level entry")
            assertTrue(
                entries.single().highlightable.not(),
                "${section.name} is rendered by another module, so its entry must not claim a control to highlight",
            )
        }
    }

    /**
     * Shortcuts is host code, not a delegated panel, and must carry its real controls.
     *
     * It was filed as delegated at first. That single mis-classification made "tab switching" find
     * nothing AND took the section out of the drift guard, so no test objected. Pinning the actual
     * controls is what stops it silently reverting to a keywords-only stub.
     */
    @Test
    fun `Shortcuts indexes its tab-switching controls`() {
        val labels = SettingsSearchIndex.builtIn.filter { it.section == SettingsSection.KEYMAP }.map { it.label }

        assertTrue("Positional" in labels, "the tab-switching chips are missing from the index: $labels")
        assertTrue("Most recently used" in labels, "the tab-switching chips are missing from the index: $labels")
    }

    /**
     * A curated entry has no `label =` line behind it, so the staleness check has to skip it. Keeping
     * that set small matters: every curated entry is one the drift guard cannot verify.
     *
     * Panel signposts are the second admitted shape. They are curated for a stronger reason than the
     * catch-alls - there is no host source to drift *from*, the target being in another window - so
     * they are exempted by carrying a panel rather than by name.
     */
    @Test
    fun `only section-level catch-alls and panel signposts are curated`() {
        val curated = SettingsSearchIndex.builtIn.filter { it.curated }
        val offenders =
            curated
                .filter { it.panel == null }
                .filter { it.label != it.section?.displayName || it.group != null }

        assertTrue(
            offenders.isEmpty(),
            "these entries opt out of the staleness check without being section-level catch-alls: " +
                offenders.map { it.resultKey },
        )
    }

    /**
     * The words a user types when they go looking for AI provider settings must find something.
     *
     * `Settings > AI Providers` was removed when the section moved into the Secret Manager panel,
     * and removing the section took its search entry with it - so "api key", "anthropic" and
     * "claude" matched nothing at all, and Settings search answered "No matching settings" for a
     * feature that exists one window away. Nothing else guards this: a panel is not a settings page,
     * so the query-time merge that covers plugin pages does not reach it.
     *
     * Pinned by search rather than by reading the index, because a keyword that is present but
     * unmatchable is the failure worth catching - `pluginPageEntry` drops everything shorter than
     * four characters, which is what "api" and "key" would have hit had this gone that route.
     */
    @Test
    fun `AI provider searches still land somewhere`() {
        listOf("api key", "anthropic", "openai", "claude", "gateway", "llm").forEach { query ->
            val hits = SettingsSearchMatcher.search(query, SettingsSearchIndex.builtIn)

            assertTrue(
                hits.any { it.entry.panel == PanelIds.SECRET_MANAGER },
                "searching '$query' no longer offers the Secret Manager panel: ${hits.map { it.entry.label }}",
            )
        }
    }

    /**
     * A signpost must not outlive the panel it points at.
     *
     * This is the RBAC invariant the rest of the search box already keeps - `SettingsContent` says
     * "a page the user cannot see is not in `pluginPages`, so it is not searchable either" - and a
     * signpost is the one entry that could break it, being declared in the index rather than merged
     * in from a registry. It is also what keeps the click from being a dead end: an unregistered
     * panel means the plugin is missing, off, incompatible or not permitted, and the row would
     * raise an empty main window over the Settings window the user was reading.
     */
    @Test
    fun `a signpost is dropped when its panel is not registered`() {
        val entries = SettingsSearchIndex.builtIn
        val signposts = entries.filter { it.panel != null }
        assertTrue(signposts.isNotEmpty(), "nothing to test - the AI provider signpost has gone missing")

        val nothingRegistered = entries.withoutUnreachableSignposts { false }
        assertTrue(
            nothingRegistered.none { it.panel != null },
            "a signpost survived with no panel registered, so a user without the plugin can still " +
                "click it: ${nothingRegistered.filter { it.panel != null }.map { it.label }}",
        )
        assertEquals(
            entries.size - signposts.size,
            nothingRegistered.size,
            "dropping signposts must not drop anything else",
        )

        // The other direction, so the filter cannot pass by dropping everything.
        assertEquals(entries, entries.withoutUnreachableSignposts { true })
    }

    /** A signpost navigates out of this window, so it must claim no section, page or control. */
    @Test
    fun `a panel signpost targets a panel and claims no control`() {
        val signposts = SettingsSearchIndex.builtIn.filter { it.panel != null }

        assertTrue(signposts.isNotEmpty(), "the AI provider signpost has gone missing")
        signposts.forEach { entry ->
            assertEquals(null, entry.section, "${entry.label} claims a section it cannot navigate to")
            assertEquals(null, entry.pluginPageId, "${entry.label} claims a plugin page as well as a panel")
            assertTrue(
                entry.highlightable.not(),
                "${entry.label} is in another window, so there is no host control here to highlight",
            )
            assertTrue(
                entry.context != null,
                "${entry.label} must name its panel in the breadcrumb - it is what says 'not in this window'",
            )
        }
    }

    @Test
    fun `keywords are lowercase and never repeat the label`() {
        SettingsSearchIndex.builtIn.forEach { entry ->
            entry.keywords.forEach { keyword ->
                assertEquals(keyword.lowercase(), keyword, "keyword '$keyword' on '${entry.label}' is not lowercase")
                assertTrue(keyword.isNotBlank(), "blank keyword on '${entry.label}'")
                assertTrue(
                    !keyword.equals(entry.label, ignoreCase = true),
                    "keyword '$keyword' just repeats the label '${entry.label}'",
                )
            }
        }
    }

    /** A plugin page is navigable by id and has nothing the host could scroll to. */
    @Test
    fun `a plugin page entry targets its page and claims no control`() {
        val entry = pluginPageEntry("jupyter-notebook", "Jupyter", "Kernel and notebook settings")

        assertEquals("jupyter-notebook", entry.pluginPageId)
        assertEquals(null, entry.section)
        assertTrue(entry.highlightable.not())
        assertEquals("Plugins", entry.breadcrumb)
    }
}
