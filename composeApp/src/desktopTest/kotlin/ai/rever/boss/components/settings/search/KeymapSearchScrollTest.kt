package ai.rever.boss.components.settings.search

import ai.rever.boss.components.settings.keymap.EditableKeymapSettings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

/**
 * Shortcuts renders its page in a `LazyColumn`, and that breaks the usual mechanism.
 *
 * Everywhere else in Settings a control asks to be brought into view itself, which is enough because
 * the whole section is composed. A `LazyColumn` composes only what is near the viewport, and a node
 * that does not exist cannot be scrolled to - so a search hit for a tab-switching chip did nothing
 * whenever the page happened to be scrolled down its shortcut list. The page now scrolls the list to
 * that item first.
 *
 * **The short viewport is the whole test.** At the harness's default size the chips are composed
 * anyway, so the assertion would pass with or without the fix and prove nothing. Forcing a height
 * that cannot reach item [ai.rever.boss.components.settings.keymap.TAB_SWITCH_ITEM_INDEX] is what
 * makes it a real check - and it doubles as the guard on that index, since inserting an item above
 * the selector moves it out of reach and fails here.
 */
class KeymapSearchScrollTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a chip below the fold is scrolled to and highlighted`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalSettingsHighlight provides
                    SettingsHighlight(group = null, label = "Positional", nonce = 1),
            ) {
                Box(modifier = Modifier.requiredWidth(600.dp).requiredHeight(VIEWPORT_HEIGHT)) {
                    EditableKeymapSettings()
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("Positional").assertIsDisplayed()
        compose.onAllNodesWithTag(HIGHLIGHT_TEST_TAG).assertCountEquals(1)
    }

    /**
     * The other half of the pair, and what stops the test above being vacuous.
     *
     * With no highlight the chip is not composed at all in this viewport - so the fact that the
     * previous test finds it displayed is the scroll doing its job, not the harness being generous.
     */
    @Test
    fun `the chip stays out of reach when the search points nowhere`() {
        compose.setContent {
            CompositionLocalProvider(LocalSettingsHighlight provides null) {
                Box(modifier = Modifier.requiredWidth(600.dp).requiredHeight(VIEWPORT_HEIGHT)) {
                    EditableKeymapSettings()
                }
            }
        }
        compose.waitForIdle()

        compose.onAllNodesWithText("Positional").assertCountEquals(0)
        compose.onAllNodesWithTag(HIGHLIGHT_TEST_TAG).assertCountEquals(0)
    }

    private companion object {
        /** Short enough that the tab-switching item is out of reach on first composition. */
        val VIEWPORT_HEIGHT = 160.dp
    }
}
