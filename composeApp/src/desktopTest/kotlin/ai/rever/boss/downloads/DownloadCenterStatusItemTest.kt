package ai.rever.boss.downloads

import ai.rever.boss.components.bars.horizontal.DownloadCenterStatusItem
import ai.rever.boss.plugin.api.TransferKind
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertTrue

/**
 * The bar itself, composed - not the strings, which [DownloadCenterTextTest] pins.
 *
 * Worth a screen because the thing that makes this widget correct is what it does
 * when there is nothing to show: it sits in the middle of the status bar, so a
 * version that composed an empty Row would push every item beside it sideways
 * whenever a download finished, and no unit test would see that.
 */
class DownloadCenterStatusItemTest {
    @get:Rule
    val rule = createComposeRule()

    // Both, so the first test does not depend on every other class tidying up.
    @Before
    @After
    fun clean() = DownloadCenter.reset()

    @Test
    fun `nothing is composed while nothing is in flight`() {
        rule.setContent {
            Row { DownloadCenterStatusItem() }
        }

        // Structural, not "no text saying Downloading": an item that composed an
        // empty label and a zero-progress bar would pass that and still push every
        // status-bar item beside it sideways whenever a download finished.
        val children = rule.onRoot().fetchSemanticsNode().children
        assertTrue(children.isEmpty(), "an empty center must draw nothing at all, not an empty row")
    }

    @Test
    fun `a transfer appears and names itself`() {
        rule.setContent {
            Row { DownloadCenterStatusItem() }
        }

        rule.runOnIdle {
            DownloadCenter.begin("p", "Docker", TransferKind.PLUGIN_INSTALL)
            DownloadCenter.progress("p", 0.4f)
        }

        rule.onNodeWithText("Downloading Docker 40%").assertIsDisplayed()
    }

    @Test
    fun `it goes away again when the transfer ends`() {
        rule.setContent {
            Row { DownloadCenterStatusItem() }
        }

        rule.runOnIdle {
            DownloadCenter.begin("p", "Docker", TransferKind.PLUGIN_INSTALL)
            DownloadCenter.progress("p", 0.4f)
        }
        rule.onNodeWithText("Downloading Docker 40%").assertIsDisplayed()

        rule.runOnIdle { DownloadCenter.end("p") }

        rule.onNodeWithText("Downloading Docker 40%").assertDoesNotExist()
    }

    @Test
    fun `several transfers are counted rather than called plugins`() {
        rule.setContent {
            Row { DownloadCenterStatusItem() }
        }

        rule.runOnIdle {
            DownloadCenter.begin("p", "Docker", TransferKind.PLUGIN_INSTALL)
            DownloadCenter.begin(DownloadCenter.APP_UPDATE_ID, "BOSS v9.5.0", TransferKind.APP_UPDATE)
        }

        rule.onNodeWithText("2 downloads…").assertIsDisplayed()
    }
}
