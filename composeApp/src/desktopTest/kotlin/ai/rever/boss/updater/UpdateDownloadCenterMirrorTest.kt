package ai.rever.boss.updater

import ai.rever.boss.downloads.DownloadCenter
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import ai.rever.boss.utils.Version
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The state-to-row mapping, which is the whole mirror.
 *
 * Worth pinning because both halves are easy to get subtly wrong in a way nobody
 * sees until an update is actually running: a row left behind after a restart is
 * required looks like a download that never finished, and an Install button
 * offered while the installer is running is a second elevated install.
 */
class UpdateDownloadCenterMirrorTest {
    private val manager = UpdateManager()

    @BeforeEach
    @AfterEach
    fun clean() = DownloadCenter.reset()

    private fun row() = DownloadCenter.transfers.value.singleOrNull()

    @Test
    fun `downloading publishes a cancellable row with progress`() {
        UpdateDownloadCenterMirror.publish(UpdateState.Downloading(0.3f), manager)

        val row = assertNotNull(row())
        assertEquals(DownloadCenter.APP_UPDATE_ID, row.info.id)
        assertEquals(TransferKind.APP_UPDATE, row.info.kind)
        assertEquals(TransferPhase.DOWNLOADING, row.info.phase)
        assertEquals(0.3f, row.info.progress)
        assertTrue(row.info.cancellable)
        assertNull(row.onInstall, "there is nothing to install until it has downloaded")
    }

    @Test
    fun `progress updates the existing row rather than adding one`() {
        UpdateDownloadCenterMirror.publish(UpdateState.Downloading(0.1f), manager)
        UpdateDownloadCenterMirror.publish(UpdateState.Downloading(0.6f), manager)

        assertEquals(1, DownloadCenter.transfers.value.size)
        assertEquals(0.6f, row()?.info?.progress)
    }

    @Test
    fun `a downloaded update offers Install and Cancel`() {
        UpdateDownloadCenterMirror.publish(UpdateState.Downloading(0.9f), manager)
        UpdateDownloadCenterMirror.publish(UpdateState.ReadyToInstall("/tmp/boss.dmg"), manager)

        val row = assertNotNull(row())
        assertEquals(TransferPhase.READY_TO_INSTALL, row.info.phase)
        assertNotNull(row.onInstall)
        // Cancel here discards the staged file, which is safe - unlike cancelling
        // an install that has started.
        assertTrue(row.info.cancellable)
    }

    @Test
    fun `installing withdraws both actions`() {
        UpdateDownloadCenterMirror.publish(UpdateState.ReadyToInstall("/tmp/boss.dmg"), manager)
        UpdateDownloadCenterMirror.publish(UpdateState.Installing, manager)

        val row = assertNotNull(row())
        assertEquals(TransferPhase.INSTALLING, row.info.phase)
        assertFalse(row.info.cancellable, "file moves plus an elevated helper are past abandoning")
    }

    @Test
    fun `states that are not work in flight leave no row`() {
        listOf(
            UpdateState.Idle,
            UpdateState.UpToDate,
            UpdateState.CheckingForUpdates,
            UpdateState.RestartRequired,
            UpdateState.Error("boom"),
            // The state a CANCELLED download lands in, so the one that most needs to
            // retract the row - and the one this list originally missed.
            UpdateState.UpdateAvailable(
                UpdateInfo(
                    available = true,
                    currentVersion = Version.parse("9.4.33")!!,
                    latestVersion = Version.parse("9.4.34")!!,
                    releaseNotes = "",
                ),
            ),
        ).forEach { state ->
            UpdateDownloadCenterMirror.publish(UpdateState.Downloading(0.5f), manager)
            UpdateDownloadCenterMirror.publish(state, manager)
            assertTrue(
                DownloadCenter.transfers.value.isEmpty(),
                "$state is not a transfer, so a progress row for it would be a lie",
            )
        }
    }
}
