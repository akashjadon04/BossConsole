package ai.rever.boss.downloads

import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The rules that keep one row honest: who owns it, when Cancel is offered, and
 * what a nested operation is allowed to do to somebody else's entry.
 *
 * Each of these is a way the status bar can lie - a row that never leaves, a
 * Cancel that corrupts a half-swapped plugin, or a fallback path removing the
 * row its caller is still filling.
 */
class DownloadCenterTest {
    @BeforeEach
    @AfterEach
    fun clean() = DownloadCenter.reset()

    @Test
    fun `begin creates one entry and nested begin joins it`() {
        val outer = DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_INSTALL)
        val inner = DownloadCenter.begin("p", "Plugin again", TransferKind.PLUGIN_UPDATE)

        assertTrue(outer, "the first begin owns the entry")
        assertFalse(inner, "a nested begin must not claim ownership")
        assertEquals(1, DownloadCenter.transfers.value.size)
        // The outer call's description wins: the inner one is a fallback inside it.
        assertEquals(
            "Plugin",
            DownloadCenter.transfers.value
                .single()
                .info.title,
        )
    }

    @Test
    fun `progress moves the row into the downloading phase`() {
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_INSTALL)
        DownloadCenter.progress("p", 0.42f)

        val info =
            DownloadCenter.transfers.value
                .single()
                .info
        assertEquals(TransferPhase.DOWNLOADING, info.phase)
        assertEquals(0.42f, info.progress)
    }

    @Test
    fun `progress is clamped`() {
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_INSTALL)
        DownloadCenter.progress("p", 3f)
        assertEquals(
            1f,
            DownloadCenter.transfers.value
                .single()
                .info.progress,
        )
    }

    @Test
    fun `leaving the download phase drops the fraction`() {
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_INSTALL)
        DownloadCenter.progress("p", 0.9f)
        DownloadCenter.phase("p", TransferPhase.INSTALLING)

        // Otherwise the row reads "Installing 90%" and sits there.
        assertNull(
            DownloadCenter.transfers.value
                .single()
                .info.progress,
        )
    }

    @Test
    fun `installing is the one phase that cannot be cancelled`() {
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_UPDATE, onCancel = {})

        DownloadCenter.progress("p", 0.5f)
        assertTrue(
            DownloadCenter.transfers.value
                .single()
                .info.cancellable,
        )

        DownloadCenter.phase("p", TransferPhase.INSTALLING)
        assertFalse(
            DownloadCenter.transfers.value
                .single()
                .info.cancellable,
            "cancelling a jar swap would leave the plugin unloaded",
        )

        DownloadCenter.phase("p", TransferPhase.READY_TO_INSTALL)
        assertTrue(
            DownloadCenter.transfers.value
                .single()
                .info.cancellable,
            "a downloaded update is a file to delete, so Cancel is offered again",
        )
    }

    @Test
    fun `a transfer with no cancel action is never cancellable`() {
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_INSTALL)
        DownloadCenter.progress("p", 0.5f)
        assertFalse(
            DownloadCenter.transfers.value
                .single()
                .info.cancellable,
        )
    }

    @Test
    fun `cancel refuses while installing and runs otherwise`() {
        var cancelled = 0
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_UPDATE, onCancel = { cancelled++ })

        DownloadCenter.phase("p", TransferPhase.INSTALLING)
        DownloadCenter.cancel("p")
        assertEquals(0, cancelled, "the gate is enforced in the center, not only in the dialog")

        DownloadCenter.progress("p", 0.1f)
        DownloadCenter.cancel("p")
        assertEquals(1, cancelled)
    }

    @Test
    fun `cancel leaves the row for its owner to remove`() {
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_UPDATE, onCancel = {})
        DownloadCenter.progress("p", 0.1f)
        DownloadCenter.cancel("p")

        // The cancelled work removes it through the same finally a completed one
        // uses; removing it here would race a transfer that finished first.
        assertEquals(1, DownloadCenter.transfers.value.size)
    }

    @Test
    fun `an Install action arrives after the row exists`() {
        var installed = 0
        DownloadCenter.begin("boss", "BOSS", TransferKind.APP_UPDATE)
        // Not a begin parameter: an Install only exists once something has
        // finished downloading, which is a later fact about an existing row.
        DownloadCenter.setActions("boss", onCancel = null, onInstall = { installed++ })
        DownloadCenter.phase("boss", TransferPhase.READY_TO_INSTALL)

        DownloadCenter.install("boss")
        assertEquals(1, installed)
    }

    @Test
    fun `end is idempotent and untargeted calls are no-ops`() {
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_INSTALL)
        DownloadCenter.end("p")
        DownloadCenter.end("p")
        // Safe in a finally, and safe after a transfer someone else already ended.
        DownloadCenter.progress("p", 0.5f)
        DownloadCenter.phase("p", TransferPhase.INSTALLING)
        DownloadCenter.cancel("p")
        DownloadCenter.install("p")

        assertTrue(DownloadCenter.transfers.value.isEmpty())
    }

    @Test
    fun `a second press does nothing - both actions are single-shot`() {
        var cancels = 0
        var installs = 0
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_UPDATE, onCancel = { cancels++ })
        DownloadCenter.setActions("p", onCancel = { cancels++ }, onInstall = { installs++ })
        DownloadCenter.progress("p", 0.4f)

        // The row keeps reading "Downloading" under the cursor until a background hop
        // and a recomposition land, so two quick presses are one gesture away.
        DownloadCenter.cancel("p")
        DownloadCenter.cancel("p")
        assertEquals(1, cancels)

        DownloadCenter.phase("p", TransferPhase.READY_TO_INSTALL)
        DownloadCenter.install("p")
        DownloadCenter.install("p")
        assertEquals(1, installs, "two elevated installs of one staged artifact")
    }

    @Test
    fun `a second operation joining one row withdraws its Cancel`() {
        var first = 0
        var second = 0
        DownloadCenter.begin("p", "Store version", TransferKind.PLUGIN_INSTALL, onCancel = {
            first++
            Unit
        })
        DownloadCenter.progress("p", 0.3f)

        // The host keys plugin work by pluginId, so an "Install Store Version" and a
        // dependency install of the same id land on one row. Cancel would abandon
        // whichever got there first, while the user was looking at the other.
        DownloadCenter.begin("p", "Dependency", TransferKind.PLUGIN_INSTALL, onCancel = {
            second++
            Unit
        })

        assertFalse(
            DownloadCenter.transfers.value
                .single()
                .info.cancellable,
        )
        DownloadCenter.cancel("p")
        assertEquals(0, first)
        assertEquals(0, second)
    }

    @Test
    fun `re-beginning with the same action is nesting, not joining`() {
        var cancels = 0
        val onCancel: () -> Unit = { cancels++ }
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_INSTALL, onCancel = onCancel)
        DownloadCenter.progress("p", 0.3f)

        // A fallback path inside one operation passes the same action, and must not
        // cost that operation its Cancel.
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_INSTALL, onCancel = onCancel)

        assertTrue(
            DownloadCenter.transfers.value
                .single()
                .info.cancellable,
        )
    }

    @Test
    fun `a non-finite fraction is indeterminate, not clamped`() {
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_INSTALL)

        // bytes / length is NaN when the length is absent or zero, and coerceIn
        // propagates NaN - straight to the progress bar.
        DownloadCenter.progress("p", Float.NaN)
        assertNull(
            DownloadCenter.transfers.value
                .single()
                .info.progress,
        )

        DownloadCenter.progress("p", Float.POSITIVE_INFINITY)
        assertNull(
            DownloadCenter.transfers.value
                .single()
                .info.progress,
        )

        DownloadCenter.progress("p", 0.5f)
        assertEquals(
            0.5f,
            DownloadCenter.transfers.value
                .single()
                .info.progress,
        )
    }

    @Test
    fun `detail can change as the steps do`() {
        DownloadCenter.begin("p", "Plugin", TransferKind.PLUGIN_INSTALL, detail = "1 of 3")

        DownloadCenter.detail("p", "2 of 3")
        assertEquals(
            "2 of 3",
            DownloadCenter.transfers.value
                .single()
                .info.detail,
        )

        DownloadCenter.detail("p", null)
        assertNull(
            DownloadCenter.transfers.value
                .single()
                .info.detail,
        )
    }

    @Test
    fun `owner distinguishes a plugin's row from the host's for one plugin id`() {
        DownloadCenter.begin("com.foo", "Foo", TransferKind.PLUGIN_INSTALL)
        DownloadCenter.begin("tools:com.foo", "Foo", TransferKind.PLUGIN_INSTALL, owner = "tools")

        // Two real downloads of the same plugin - one the host started, one a plugin
        // did. The id alone cannot tell them apart once the prefix is stripped.
        assertEquals(listOf(null, "tools"), DownloadCenter.transfers.value.map { it.info.owner })
    }

    @Test
    fun `rows keep their insertion order as progress arrives`() {
        DownloadCenter.begin("a", "A", TransferKind.PLUGIN_INSTALL)
        DownloadCenter.begin("b", "B", TransferKind.PLUGIN_INSTALL)
        DownloadCenter.progress("a", 0.5f)

        // A map would let the dialog's rows swap places under the pointer.
        assertEquals(listOf("a", "b"), DownloadCenter.transfers.value.map { it.info.id })
    }
}
