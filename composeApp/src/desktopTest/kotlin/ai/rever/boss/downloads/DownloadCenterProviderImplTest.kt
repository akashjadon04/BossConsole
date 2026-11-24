package ai.rever.boss.downloads

import ai.rever.boss.plugin.api.TransferKind
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The id prefix is a security boundary, not tidiness: without it any installed
 * plugin could address the host's app-update row to withdraw its Cancel, fake its
 * progress, or fabricate a row, and two plugins choosing `"update"` would collide.
 *
 * It has to be invisible to its owner, or it removes the only key a plugin can match
 * its own work by - which is the feature the whole change exists for. Both halves
 * are pinned here.
 */
class DownloadCenterProviderImplTest {
    @BeforeEach
    @AfterEach
    fun clean() = DownloadCenter.reset()

    private fun provider(prefix: String?) = DownloadCenterProviderImpl(idPrefix = prefix)

    @Test
    fun `a plugin's id is qualified in the center`() {
        provider("com.example.tools").begin("update", "Tools", TransferKind.PLUGIN_UPDATE)

        assertEquals(
            "com.example.tools:update",
            DownloadCenter.transfers.value
                .single()
                .info.id,
        )
    }

    @Test
    fun `a plugin reads its own id back unqualified`() {
        val plugin = provider("com.example.tools")
        plugin.begin("update", "Tools", TransferKind.PLUGIN_UPDATE)

        // Otherwise the plugin never learns what its row is called, and "is this one
        // busy?" can never be answered.
        assertEquals(listOf("update"), plugin.transfers.value.map { it.id })
    }

    @Test
    fun `another plugin's row stays qualified and is not addressable`() {
        provider("com.example.other").begin("update", "Other", TransferKind.PLUGIN_UPDATE)
        val plugin = provider("com.example.tools")

        assertEquals(listOf("com.example.other:update"), plugin.transfers.value.map { it.id })

        // Naming it does not reach it: this opens the caller's OWN row instead.
        plugin.begin("com.example.other:update", "Impostor", TransferKind.PLUGIN_INSTALL)
        assertEquals(
            setOf("com.example.other:update", "com.example.tools:com.example.other:update"),
            DownloadCenter.transfers.value
                .map { it.info.id }
                .toSet(),
        )
    }

    @Test
    fun `a plugin cannot touch the host's app update`() {
        DownloadCenter.begin(DownloadCenter.APP_UPDATE_ID, "BOSS v9.9.9", TransferKind.APP_UPDATE, onCancel = {})
        DownloadCenter.progress(DownloadCenter.APP_UPDATE_ID, 0.5f)

        val plugin = provider("com.example.tools")
        val handle = plugin.begin(DownloadCenter.APP_UPDATE_ID, "Not BOSS", TransferKind.APP_UPDATE)
        handle.phase(ai.rever.boss.plugin.api.TransferPhase.INSTALLING)
        handle.done()

        // The app update kept its progress, its phase and its Cancel; the plugin's
        // attempt became a row of its own, which its own done() then removed.
        val appRow = DownloadCenter.transfers.value.single { it.info.id == DownloadCenter.APP_UPDATE_ID }
        assertEquals(0.5f, appRow.info.progress)
        assertTrue(appRow.info.cancellable, "a plugin must not be able to withdraw the host's Cancel")
    }

    @Test
    fun `a host row keyed by pluginId is visible to that plugin unchanged`() {
        // This is the headline case: the host installs docker, and the Toolbox's own
        // button for docker reads busy. It only works because host rows are unqualified.
        DownloadCenter.begin("ai.rever.boss.plugin.dynamic.docker", "Docker", TransferKind.PLUGIN_INSTALL)

        val toolbox = provider("ai.rever.boss.plugin.dynamic.pluginmanager")

        assertEquals(listOf("ai.rever.boss.plugin.dynamic.docker"), toolbox.transfers.value.map { it.id })
    }

    @Test
    fun `an unprefixed provider is the host's own view`() {
        val host = provider(null)
        host.begin("boss-app-update", "BOSS", TransferKind.APP_UPDATE)

        assertEquals(
            "boss-app-update",
            DownloadCenter.transfers.value
                .single()
                .info.id,
        )
        assertNull(
            DownloadCenter.transfers.value
                .single()
                .onCancel,
        )
    }
}
