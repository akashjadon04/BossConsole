package ai.rever.boss.platform

import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The payload side of the drop target, which needs no window server.
 *
 * The modifier itself does - a real drag comes from the OS - but reading a `Transferable` is
 * ordinary code, and it is where the failure modes live: a drag source is another process, so
 * every one of these is something a misbehaving or already-finished source can hand over.
 */
class DroppedFileListTest {
    private val temps = mutableListOf<File>()

    private fun tempDir(): File = createTempDirectory("dropped").toFile().also { temps += it }

    @AfterTest
    fun cleanup() {
        temps.forEach { it.deleteRecursively() }
    }

    private val fileFlavor: DataFlavor = DataFlavor.javaFileListFlavor

    private inner class FakeTransferable(
        private val supported: Boolean,
        private val data: (() -> Any?)? = null,
    ) : Transferable {
        override fun getTransferDataFlavors(): Array<DataFlavor> = if (supported) arrayOf(fileFlavor) else emptyArray()

        override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = supported && flavor == fileFlavor

        override fun getTransferData(flavor: DataFlavor): Any? {
            if (!supported) throw UnsupportedFlavorException(flavor)
            return data?.invoke()
        }
    }

    private fun files(entries: List<File>) = FakeTransferable(supported = true, data = { entries })

    private fun files(vararg entries: File) = files(entries.toList())

    @Test
    fun `a null or non-file transferable carries nothing`() {
        assertFalse(null.carriesFiles())
        assertFalse(FakeTransferable(supported = false).carriesFiles())
        assertEquals(emptyList(), null.filePathsOrEmpty())
        assertEquals(emptyList(), FakeTransferable(supported = false).filePathsOrEmpty())
    }

    @Test
    fun `files come back as absolute paths`() {
        val dir = tempDir()
        val a = File(dir, "a.txt").apply { writeText("a") }
        val b = File(dir, "b.kt").apply { writeText("b") }

        assertTrue(files(a, b).carriesFiles())
        assertEquals(listOf(a.absolutePath, b.absolutePath), files(a, b).filePathsOrEmpty())
    }

    /**
     * The finding this exists for: Finder hands a folder over through the same flavour, and
     * the caller routes by extension, so a folder would have opened as a source file.
     */
    @Test
    fun `directories are dropped from the list`() {
        val dir = tempDir()
        val file = File(dir, "keep.txt").apply { writeText("x") }
        val folder = File(dir, "a-folder").apply { mkdirs() }

        assertEquals(listOf(file.absolutePath), files(file, folder).filePathsOrEmpty())
    }

    @Test
    fun `a folder-only drop opens nothing`() {
        val dir = tempDir()
        val folder = File(dir, "only").apply { mkdirs() }
        assertEquals(emptyList(), files(folder).filePathsOrEmpty())
    }

    /** One select-all in Finder should not open a tab, and an LSP session, per file. */
    @Test
    fun `a huge drop is capped`() {
        val dir = tempDir()
        val many = (1..MAX_FILES_PER_DROP + 5).map { File(dir, "f$it.txt").apply { writeText("x") } }
        assertEquals(MAX_FILES_PER_DROP, files(many).filePathsOrEmpty().size)
    }

    /** `getTransferData` reaches into the source process and is documented to throw. */
    @Test
    fun `a throwing source is a no-op, not a crash`() {
        val exploding = FakeTransferable(supported = true, data = { error("source went away") })
        assertEquals(emptyList(), exploding.filePathsOrEmpty())
    }

    /**
     * `as? List<File>` only proves it is a `List`, so the wrong element type reaches the map
     * and raises ClassCastException there. Behaviour is already correct; this pins it rather
     * than leaving the next reader to trace the erasure.
     */
    @Test
    fun `a list of the wrong element type is a no-op`() {
        val wrong = FakeTransferable(supported = true, data = { listOf("not-a-file") })
        assertEquals(emptyList(), wrong.filePathsOrEmpty())
    }

    @Test
    fun `null data is a no-op`() {
        assertEquals(emptyList(), FakeTransferable(supported = true, data = { null }).filePathsOrEmpty())
    }
}
