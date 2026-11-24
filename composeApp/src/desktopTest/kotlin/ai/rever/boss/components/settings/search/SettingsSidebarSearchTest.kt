package ai.rever.boss.components.settings.search

import ai.rever.boss.components.plugin.PanelIds
import ai.rever.boss.components.settings.sidebar.SettingsSection
import ai.rever.boss.components.settings.sidebar.SettingsSidebar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The rail's two modes.
 *
 * Layout-level rather than logic-level on purpose: whether typing actually swaps the section list
 * for a result list is a placement question, and placement questions pass every non-visual gate in
 * this repo before failing on screen.
 *
 * Assertions are `assertExists` rather than `assertIsDisplayed` for anything in a list. The rail is
 * a plain scrolling `Column`, so all 19 sections compose whether or not they fit the test window,
 * and pinning visibility would be pinning the harness's window height.
 */
class SettingsSidebarSearchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `an empty query shows the section rail`() {
        compose.setContent {
            SettingsSidebar(selectedSection = SettingsSection.FLUCK, onSectionChange = {})
        }

        compose.onNodeWithText("Performance").assertExists()
        compose.onNodeWithText("Advanced").assertExists()
        compose.onNodeWithText("Search settings").assertIsDisplayed()
    }

    @Test
    fun `typing replaces the section rail with results`() {
        compose.setContent {
            var query by remember { mutableStateOf("") }
            val hits = SettingsSearchMatcher.search(query, SettingsSearchIndex.builtIn)
            SettingsSidebar(
                selectedSection = SettingsSection.FLUCK,
                onSectionChange = {},
                query = query,
                onQueryChange = { query = it },
                hits = hits,
                onHitPicked = {},
            )
        }

        // By set-text action rather than by the placeholder: the placeholder is its own Text node
        // inside the decoration box, and typing at it would find no editable target.
        compose.onNode(hasSetTextAction()).performTextReplacement("panel scrollbar thickness")

        compose.onNodeWithText("Panel Scrollbar Thickness").assertExists()
        // onAllNodes, because a breadcrumb is deliberately not unique: several controls share
        // one group, and that is exactly what the breadcrumb is for.
        compose.onAllNodesWithText("Scrollbars > Scrollbar Thickness").onFirst().assertExists()

        // The rail is gone while a query is present - this is the swap, not merely a filter.
        compose.onNodeWithText("Focus Mode").assertDoesNotExist()
    }

    @Test
    fun `picking a result reports the entry it names`() {
        var picked: SettingsSearchEntry? = null
        val hits = SettingsSearchMatcher.search("panel scrollbar thickness", SettingsSearchIndex.builtIn)

        compose.setContent {
            SettingsSidebar(
                selectedSection = SettingsSection.FLUCK,
                onSectionChange = {},
                query = "panel scrollbar thickness",
                hits = hits,
                onHitPicked = { picked = it.entry },
            )
        }

        compose.onNodeWithText("Panel Scrollbar Thickness").performClick()

        assertEquals("Panel Scrollbar Thickness", picked?.label)
        assertEquals("Scrollbar Thickness", picked?.group)
        assertEquals(SettingsSection.SCROLLBAR, picked?.section)
    }

    /**
     * The signpost has to read as a signpost, and only the rail can show that.
     *
     * Its label is "AI Providers", the same words the removed section used, so on the label alone a
     * reader has no way to tell this row from the one that used to open inside this window. The
     * breadcrumb is the entire difference - and a breadcrumb is a second `Text` node under the
     * label, which every non-visual gate in this repo is happy to let go missing.
     */
    @Test
    fun `the AI provider signpost names the panel it will open`() {
        var picked: SettingsSearchEntry? = null
        val hits = SettingsSearchMatcher.search("anthropic", SettingsSearchIndex.builtIn)

        compose.setContent {
            SettingsSidebar(
                selectedSection = SettingsSection.FLUCK,
                onSectionChange = {},
                query = "anthropic",
                hits = hits,
                onHitPicked = { picked = it.entry },
            )
        }

        compose.onNodeWithText("AI Providers").assertExists()
        compose.onAllNodesWithText("Plugins > Secret Manager panel").onFirst().assertExists()

        compose.onNodeWithText("AI Providers").performClick()

        assertEquals(PanelIds.SECRET_MANAGER, picked?.panel)
        assertEquals(null, picked?.section, "a signpost that names a section would navigate this window instead")
    }

    @Test
    fun `a query with no matches says so rather than showing an empty rail`() {
        compose.setContent {
            SettingsSidebar(
                selectedSection = SettingsSection.FLUCK,
                onSectionChange = {},
                query = "zzzzqqqq",
                hits = emptyList(),
            )
        }

        compose.onNodeWithText("No matching settings").assertExists()
    }
}
