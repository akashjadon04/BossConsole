package ai.rever.boss.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A recent file that is not on disk right now is **hidden, not forgotten**.
 *
 * Two bugs in sequence made this worth pinning. First, nothing filtered at all, so a deleted file
 * stayed on the home screen and opened an empty editor. Then the fix pruned the list and persisted
 * it - and `File.exists()` is false for an unmounted volume or a disconnected share, which is normal
 * at login, exactly when the load runs. So recent files on a network share were permanently deleted
 * by the act of opening one local file, while the KDoc claimed the opposite.
 *
 * The recorded list and the displayed list are now separate, and only the recorded one is written.
 */
class RecentFileVisibilityTest {
    private fun file(path: String) = RecentFile(path = path, name = path.substringAfterLast('/'), lastOpened = 0L)

    private val onDisk = file("/local/present.kt")
    private val absent = file("/Volumes/share/away.kt")

    @Test
    fun `an absent file is hidden from the displayed list`() {
        val visible = visibleFiles(listOf(onDisk, absent)) { it == onDisk.path }

        assertEquals(listOf(onDisk), visible)
    }

    @Test
    fun `hiding does not remove the entry from the list it was given`() {
        // The recorded list is the input and is untouched: this is what gets persisted, so an entry
        // on an absent volume survives to reappear when the volume returns.
        val all = listOf(onDisk, absent)

        visibleFiles(all) { it == onDisk.path }

        assertTrue(absent in all, "visibleFiles must not mutate or drop from the recorded list")
        assertEquals(2, all.size)
    }

    @Test
    fun `everything present means nothing hidden`() {
        val all = listOf(onDisk, absent)

        assertEquals(all, visibleFiles(all) { true })
    }

    @Test
    fun `nothing present means an empty display over an intact record`() {
        val all = listOf(onDisk, absent)

        assertTrue(visibleFiles(all) { false }.isEmpty())
        assertEquals(2, all.size)
    }

    @Test
    fun `order is preserved, since the list is ordered by recency`() {
        val a = file("/a.kt")
        val b = file("/b.kt")
        val c = file("/c.kt")

        assertEquals(listOf(a, c), visibleFiles(listOf(a, b, c)) { it != b.path })
    }
}
