package ai.rever.boss.updater

import ai.rever.boss.downloads.DownloadCenter
import ai.rever.boss.plugin.api.TransferKind
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The two things a user can do to an update from the banner, asserted against the rendered tree.
 *
 * The banner is the surface people actually look at - it is the topmost chrome in the window -
 * and until this it was the weaker of the two: the bottom bar's progress item opened a dialog
 * with Cancel and Install, while the banner showing the same transfer offered no way to stop it
 * and no way in. Both states are covered because they abandon different things:
 * `Downloading` cancels bytes arriving, `ReadyToInstall` deletes an artifact already on disk.
 *
 * A rendering test rather than a source one, unlike [UpdateHandleGuardTest]. What can regress
 * here is a button that stops being reachable - dropped from the layout, or placed where it does
 * not draw - and neither shows up in a signature. `assertIsDisplayed` is the part that matters:
 * existing in the semantics tree is not the same as being on screen.
 */
class UpdateBannerActionsTest {
    @get:Rule
    val rule = createComposeRule()

    private val pressed = mutableListOf<String>()

    private fun setBanner(state: UpdateState) {
        rule.setContent {
            UpdateBanner(
                updateState = state,
                onInstallUpdate = { pressed += "install" },
                onCancelDownload = { pressed += "cancel" },
                onDiscardDownload = { pressed += "discard" },
            )
        }
    }

    @Test
    fun `a download in progress can be cancelled from the banner`() {
        setBanner(UpdateState.Downloading(progress = 0.4f))

        rule.onNodeWithText("Cancel").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()

        // cancelDownload, not discardDownload: nothing is on disk yet to throw away.
        assertEquals(listOf("cancel"), pressed)
    }

    @Test
    fun `a downloaded update offers both Install Now and Cancel`() {
        setBanner(UpdateState.ReadyToInstall(downloadPath = "/tmp/BOSS.dmg"))

        // Install Now was always here; Cancel is what the banner was missing, and without it
        // the only way to be rid of a staged update was the dialog behind the bottom bar.
        rule.onNodeWithText("Install Now").assertIsDisplayed()
        rule.onNodeWithText("Cancel").assertIsDisplayed()

        rule.onNodeWithText("Cancel").performClick()
        // discard, NOT cancel: this one deletes the staged artifact, and routing it to
        // cancelDownload would silently do nothing (that method refuses anything not Downloading).
        assertEquals(listOf("discard"), pressed)

        rule.onNodeWithText("Install Now").performClick()
        assertEquals(listOf("discard", "install"), pressed)
    }

    @Before
    @After
    fun clearCenter() = DownloadCenter.reset()

    /** The dialog only renders rows, so it needs one to be distinguishable from nothing. */
    private fun seedAppUpdateRow() {
        DownloadCenter.begin(
            id = DownloadCenter.APP_UPDATE_ID,
            title = "BOSS v9.5.3",
            kind = TransferKind.APP_UPDATE,
            detail = "Application update",
        )
    }

    @Test
    fun `clicking the banner opens the download center, and Cancel does not`() {
        seedAppUpdateRow()
        setBanner(UpdateState.Downloading(progress = 0.4f))

        rule.onNodeWithText("Minimize").assertDoesNotExist()

        // Cancel is a TextButton inside the clickable row. If it stopped consuming its own press,
        // one click would both cancel the download AND open the dialog behind it.
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(listOf("cancel"), pressed)
        rule.onNodeWithText("Minimize").assertDoesNotExist()

        rule.onNodeWithText("Downloading... 40%").performClick()
        rule.onNodeWithText("Minimize").assertIsDisplayed()
    }

    @Test
    fun `the dialog survives the download finishing`() {
        seedAppUpdateRow()
        var state by mutableStateOf<UpdateState>(UpdateState.Downloading(progress = 0.9f))
        rule.setContent {
            UpdateBanner(
                updateState = state,
                onInstallUpdate = { pressed += "install" },
                onCancelDownload = { pressed += "cancel" },
                onDiscardDownload = { pressed += "discard" },
            )
        }

        rule.onNodeWithText("Downloading... 90%").performClick()
        rule.onNodeWithText("Minimize").assertIsDisplayed()

        // The regression: showDialog used to live inside the Downloading branch, so finishing the
        // download disposed that subtree and took the open dialog with it - at the exact moment
        // its row gained an Install button. The row itself survives the transition, and the bottom
        // bar keeps its dialog open across it, so the banner's copy was the only thing vanishing.
        state = UpdateState.ReadyToInstall(downloadPath = "/tmp/BOSS.dmg")
        rule.waitForIdle()

        rule.onNodeWithText("Minimize").assertIsDisplayed()
        rule.onNodeWithText("Install Now").assertIsDisplayed()
    }

    @Test
    fun `the downloading banner still shows its progress text`() {
        setBanner(UpdateState.Downloading(progress = 0.4f))

        // Adding a button to the row must not push the label out of the layout: the Row gives
        // the progress bar weight(1f), so a wider trailing action shrinks the bar, not the text.
        rule.onNodeWithText("Downloading... 40%").assertIsDisplayed()
    }
}
