package ai.rever.boss.platform

import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pins the rule that stops one download destroying another's file.
 *
 * This had no test at all, which is how three of the four bugs below survived: the counter
 * returned a colliding path once it gave up, two downloads racing the same name both got it,
 * and a released name was never reusable because nothing released it.
 */
class UniqueFilePathTest {
    private companion object {
        const val OWNER = "download-a"
        const val OTHER_OWNER = "download-b"
    }

    private val temps = mutableListOf<File>()

    private fun tempDir(): File = createTempDirectory("unique-path").toFile().also { temps += it }

    @AfterTest
    fun cleanup() {
        temps.forEach { it.deleteRecursively() }
    }

    private fun unique(
        dir: File,
        name: String,
        owner: String = OWNER,
    ): String = FileSystemUtils.generateUniqueFilePath(dir.absolutePath, name, owner)

    private fun release(
        path: String,
        owner: String = OWNER,
    ) = FileSystemUtils.releaseFilePath(path, owner)

    // Named apart from release() on purpose: as an overload, release(a, b) silently bound
    // b as the *owner* rather than releasing both, and the release then did nothing.
    private fun releaseAll(vararg paths: String) = paths.forEach { release(it) }

    @Test
    fun `a free name is returned unchanged`() {
        val dir = tempDir()
        val path = unique(dir, "report.pdf")
        assertEquals(File(dir, "report.pdf").absolutePath, path)
        release(path)
    }

    @Test
    fun `an existing file pushes the name to a numbered suffix`() {
        val dir = tempDir()
        File(dir, "report.pdf").writeText("first")

        val second = unique(dir, "report.pdf")
        assertEquals("report (1).pdf", File(second).name)
        File(second).writeText("second")

        val third = unique(dir, "report.pdf")
        assertEquals("report (2).pdf", File(third).name)
        releaseAll(second, third)

        assertEquals("first", File(dir, "report.pdf").readText(), "the original must be untouched")
    }

    @Test
    fun `an extensionless name still gets a suffix`() {
        val dir = tempDir()
        File(dir, "LICENSE").writeText("x")
        val path = unique(dir, "LICENSE")
        assertEquals("LICENSE (1)", File(path).name)
        release(path)
    }

    /**
     * The bug the claim mechanism exists for. A download's file does not appear until bytes
     * arrive, so `exists()` says "free" to every caller until the first write lands.
     */
    @Test
    fun `two callers racing one name do not get the same path`() {
        val dir = tempDir()
        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val results =
                (1..threads)
                    .map {
                        pool.submit<String> {
                            start.await(5, TimeUnit.SECONDS)
                            // A distinct owner each, which is the real shape: one per download.
                            unique(dir, "race.bin", "dl-$it")
                        }
                    }.also { start.countDown() }
                    .map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(threads, results.toSet().size, "every caller must get its own path")
            results.forEachIndexed { i, path -> release(path, "dl-${i + 1}") }
        } finally {
            pool.shutdownNow()
        }
    }

    /** A claim is a reservation, not a lease: releasing it puts the name back in the pool. */
    @Test
    fun `a released name is handed out again`() {
        val dir = tempDir()
        val first = unique(dir, "note.txt")
        val second = unique(dir, "note.txt")
        assertNotEquals(first, second, "the unreleased claim must still block")

        releaseAll(first, second)
        val reused = unique(dir, "note.txt")
        assertEquals(first, reused, "nothing holds the name and no file exists, so it is free")
        release(reused)
    }

    /**
     * The old loop stopped at 1000 and returned the *colliding* path, so a directory that
     * pathological overwrote the file the counter exists to protect. The fallback name only
     * has to not collide.
     */
    @Test
    fun `exhausting the numbered suffixes never returns an existing file`() {
        val dir = tempDir()
        File(dir, "x.dat").writeText("keep")
        for (i in 1..999) {
            File(dir, "x ($i).dat").createNewFile()
        }

        val path = unique(dir, "x.dat")

        assertTrue(File(path).parentFile.absolutePath == dir.absolutePath, "must stay in the directory")
        assertTrue(!File(path).exists(), "an occupied path would be overwritten by the caller")
        assertTrue(File(path).name.endsWith(".dat"), "the extension must survive the fallback")
        release(path)
    }

    /**
     * The claim is owned. Without that, the save-dialog path - which returns whatever name
     * the user typed, and never claims it - would release a claim the auto path was holding,
     * and a third download could then take a path still being written to.
     */
    @Test
    fun `one download cannot release another's claim`() {
        val dir = tempDir()
        val mine = unique(dir, "shared.bin")

        release(mine, OTHER_OWNER)

        val next = unique(dir, "shared.bin", OTHER_OWNER)
        assertNotEquals(mine, next, "a stranger's release must not free the claim")

        release(mine)
        release(next, OTHER_OWNER)
    }

    /** APFS and NTFS are case-insensitive, so these two names are one file. */
    @Test
    fun `claims are case-insensitive`() {
        val dir = tempDir()
        val lower = unique(dir, "Report.PDF")
        val upper = unique(dir, "report.pdf", OTHER_OWNER)
        assertNotEquals(lower.lowercase(), upper.lowercase(), "case alone must not look like a free name")
        release(lower)
        release(upper, OTHER_OWNER)
    }

    @Test
    fun `a missing directory is created rather than failing`() {
        val dir = File(tempDir(), "nested/deeper")
        val path = unique(dir, "a.txt")
        assertTrue(dir.isDirectory, "the directory should have been created")
        assertEquals(File(dir, "a.txt").absolutePath, path)
        release(path)
    }
}
