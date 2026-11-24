package ai.rever.boss.downloads

import ai.rever.boss.plugin.api.TransferInfo
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The bar and the dialog say what is happening in one line each, and that line is
 * the only thing distinguishing two simultaneous transfers. Pinned here for the
 * same reason `engineDownloadStatus` is: a wrong verb reads as a hang.
 */
class DownloadCenterTextTest {
    private fun info(
        id: String = "p",
        title: String = "Toolbox",
        kind: TransferKind = TransferKind.PLUGIN_INSTALL,
        phase: TransferPhase = TransferPhase.DOWNLOADING,
        progress: Float? = null,
    ) = TransferInfo(id = id, title = title, kind = kind, phase = phase, progress = progress)

    @Test
    fun `a determinate download names its percentage`() {
        assertEquals("Downloading 72%", transferStatusLine(info(progress = 0.725f)))
    }

    @Test
    fun `an unknown size stays indeterminate`() {
        assertEquals("Downloading…", transferStatusLine(info(progress = null)))
    }

    @Test
    fun `an update says updating once it starts installing`() {
        val updating = info(kind = TransferKind.PLUGIN_UPDATE, phase = TransferPhase.INSTALLING)
        assertEquals("Updating…", transferStatusLine(updating))

        val installing = info(kind = TransferKind.PLUGIN_INSTALL, phase = TransferPhase.INSTALLING)
        assertEquals("Installing…", transferStatusLine(installing))
    }

    @Test
    fun `a stale fraction is not shown next to a non-download phase`() {
        // The fraction is a DOWNLOAD fraction: "Installing 90%" would read as an
        // install that is 90% done and stuck.
        val installing = info(phase = TransferPhase.INSTALLING, progress = 0.9f)
        assertEquals("Installing…", transferStatusLine(installing))
    }

    @Test
    fun `a downloaded update waits rather than reporting progress`() {
        val ready = info(kind = TransferKind.APP_UPDATE, phase = TransferPhase.READY_TO_INSTALL)
        assertEquals("Ready to install", transferStatusLine(ready))
    }

    @Test
    fun `the bar names a single transfer`() {
        assertEquals("Downloading Toolbox 40%", transferBarLabel(listOf(info(progress = 0.4f))))
    }

    @Test
    fun `the bar counts several without calling them plugins`() {
        // The application's own update shares this bar, so "2 plugins" would be a
        // lie exactly when two kinds are running.
        val items =
            listOf(
                info(id = "a", progress = 0.4f),
                info(id = "boss", title = "BOSS v9.5.0", kind = TransferKind.APP_UPDATE, progress = 0.2f),
            )
        assertEquals("2 downloads…", transferBarLabel(items))
    }

    @Test
    fun `overall progress needs every size to be known`() {
        val known = listOf(info(id = "a", progress = 0.5f), info(id = "b", progress = 1f))
        assertEquals(0.75f, overallProgress(known))

        val mixed = listOf(info(id = "a", progress = 0.9f), info(id = "b", progress = null))
        assertNull(overallProgress(mixed), "averaging in an unknown makes the bar jump backwards")
    }

    @Test
    fun `a downloaded update counts as finished for the bar`() {
        val ready = info(kind = TransferKind.APP_UPDATE, phase = TransferPhase.READY_TO_INSTALL)
        // Otherwise the bar sweeps indeterminately beside "Ready to install".
        assertEquals(1f, overallProgress(listOf(ready)))
    }

    @Test
    fun `nothing in flight has no label and no bar`() {
        assertEquals("", transferBarLabel(emptyList()))
        assertNull(overallProgress(emptyList()))
    }
}
