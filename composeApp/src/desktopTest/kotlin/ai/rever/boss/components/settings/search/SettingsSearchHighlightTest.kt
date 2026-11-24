package ai.rever.boss.components.settings.search

import ai.rever.boss.components.settings.keymap.EditableKeymapSettings
import ai.rever.boss.components.settings.sections.PerformanceSettings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import org.junit.Rule
import org.junit.Test

/**
 * The test the (group, label) identity exists for.
 *
 * Performance renders "Warning Threshold" twice - once under "Memory Thresholds", once under "CPU
 * Thresholds" - and the same again for "Critical Threshold". A highlight keyed on the label alone
 * compiles, passes every unit test, and then lights up both rows while scrolling to whichever was
 * composed last. Nothing but a real composition can see that, which is why this is a Compose test
 * and not an assertion about the index.
 */
class SettingsSearchHighlightTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a duplicated label highlights only the row in the named group`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalSettingsHighlight provides
                    SettingsHighlight(group = "CPU Thresholds", label = "Warning Threshold", nonce = 1),
            ) {
                PerformanceSettings()
            }
        }

        compose.onAllNodesWithTag(HIGHLIGHT_TEST_TAG).assertCountEquals(1)
    }

    @Test
    fun `the other group's row is the one that lights up when it is named`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalSettingsHighlight provides
                    SettingsHighlight(group = "Memory Thresholds", label = "Warning Threshold", nonce = 1),
            ) {
                PerformanceSettings()
            }
        }

        compose.onAllNodesWithTag(HIGHLIGHT_TEST_TAG).assertCountEquals(1)
    }

    /** A group header is indexed as (group = null, label = title), and must match itself. */
    @Test
    fun `a group header is highlightable in its own right`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalSettingsHighlight provides
                    SettingsHighlight(group = null, label = "Memory Thresholds", nonce = 1),
            ) {
                PerformanceSettings()
            }
        }

        compose.onAllNodesWithTag(HIGHLIGHT_TEST_TAG).assertCountEquals(1)
    }

    /** With nothing requested, nothing is tagged - the section renders exactly as it always did. */
    @Test
    fun `no highlight is drawn when the search has not pointed anywhere`() {
        compose.setContent {
            CompositionLocalProvider(LocalSettingsHighlight provides null) {
                PerformanceSettings()
            }
        }

        compose.onAllNodesWithTag(HIGHLIGHT_TEST_TAG).assertCountEquals(0)
    }

    /**
     * The Shortcuts tab-switching chips highlight, which is why the highlight machinery lives in
     * commonMain.
     *
     * While it sat in desktopMain this was impossible: `EditableKeymapSettings` is commonMain and
     * could not reference the modifier at all, so these controls were findable by search and then
     * did nothing when picked. Mounting the real page is the only way to see that, since the
     * source-set boundary is invisible to any assertion about the index.
     */
    @Test
    fun `a Shortcuts tab-switching chip highlights`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalSettingsHighlight provides
                    SettingsHighlight(group = null, label = "Positional", nonce = 1),
            ) {
                EditableKeymapSettings()
            }
        }

        compose.onAllNodesWithTag(HIGHLIGHT_TEST_TAG).assertCountEquals(1)
    }

    /** The other chip, so the two are not matching each other's label. */
    @Test
    fun `the other tab-switching chip highlights on its own label`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalSettingsHighlight provides
                    SettingsHighlight(group = null, label = "Most recently used", nonce = 1),
            ) {
                EditableKeymapSettings()
            }
        }

        compose.onAllNodesWithTag(HIGHLIGHT_TEST_TAG).assertCountEquals(1)
    }

    /**
     * A label that exists but under a different group must not match. This is the stale-entry case:
     * the index says "Warning Threshold" lives under a group that no longer holds it.
     */
    @Test
    fun `a label under an unknown group highlights nothing`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalSettingsHighlight provides
                    SettingsHighlight(group = "Nonexistent Group", label = "Warning Threshold", nonce = 1),
            ) {
                PerformanceSettings()
            }
        }

        compose.onAllNodesWithTag(HIGHLIGHT_TEST_TAG).assertCountEquals(0)
    }
}
