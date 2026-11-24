package ai.rever.boss.orchestrator

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Symlink creation is the one step here that is not portable — it needs Developer Mode or an
 * administrator on Windows — so the two symlink cases skip themselves when the platform will
 * not create one. The containment rule they exercise is also asserted without symlinks by the
 * "outside every root" and "dot-dot" cases, which run everywhere.
 */
class AllowedRootsTest {
    private val tempDirs = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        tempDirs.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        Files
            .createTempDirectory("boss-roots-test")
            .toFile()
            .also { tempDirs.add(it) }

    /** Returns null when this platform will not let the test create a symlink. */
    private fun symlink(
        link: File,
        target: File,
    ): File? =
        try {
            Files.createSymbolicLink(link.toPath(), target.toPath()).toFile()
        } catch (_: Exception) {
            null
        }

    @Test
    fun `a file inside a root resolves`() {
        val root = tempDir()
        val file = File(root, "src/Main.kt").also { it.parentFile.mkdirs() }
        file.writeText("fun main() {}")

        val resolved = AllowedRoots.of(root).resolve(file)

        assertNotNull(resolved)
        assertEquals("fun main() {}", resolved.readText())
    }

    @Test
    fun `a path that does not exist yet is judged on where it would land`() {
        val root = tempDir()
        val roots = AllowedRoots.of(root)

        assertNotNull(roots.resolve(File(root, "not/created/yet.kt")))
        assertNull(roots.resolve(File(root.parentFile, "outside-not-created.kt")))
    }

    @Test
    fun `a path outside every root is refused`() {
        val root = tempDir()
        val elsewhere = tempDir()
        val file = File(elsewhere, "notes.txt").also { it.writeText("data") }

        assertNull(AllowedRoots.of(root).resolve(file))
    }

    @Test
    fun `a dot-dot path that leaves the root is refused`() {
        val parent = tempDir()
        val root = File(parent, "project").also { it.mkdirs() }
        val outside = File(parent, "outside").also { it.mkdirs() }
        File(outside, "notes.txt").writeText("data")

        val roots = AllowedRoots.of(root)

        assertNull(roots.resolve(File(root, "../outside/notes.txt")))
        // ...including when the walk goes up through a directory that does not exist, which
        // no lexical check of the string sees.
        assertNull(roots.resolve(File(root, "absent/../../outside/notes.txt")))
    }

    @Test
    fun `a dot-dot path to a target that does not exist yet is refused`() {
        // Every other dot-dot case has an existing target, so all of them exit through the
        // "a tail component exists" guard. This one reaches the end of the walk with nothing
        // to stat — the shape a caller that creates the file would use.
        //
        // Measured, not assumed: this passes with or without the `normalize()` in
        // `appendAbsentTail`, because `Path.relativize` on JDK 12+ has already collapsed the
        // `..` before the walk begins. So the assertion pins the invariant at the API
        // boundary rather than pinning that one line, and it is what turns a change in that
        // JDK behaviour — or a refactor that stops routing through `relativize` — into a red
        // test instead of a silently widened root.
        val parent = tempDir()
        val root = File(parent, "project").also { it.mkdirs() }
        File(parent, "outside").also { it.mkdirs() }

        val roots = AllowedRoots.of(root)

        assertNull(roots.resolve(File(root, "absent/../../outside/created-later.txt")))
    }

    @Test
    fun `a file in any granted root resolves`() {
        // `of(vararg)` was only ever exercised with one usable root, so `roots.any` was
        // effectively a single-element check.
        val first = tempDir()
        val second = tempDir()
        val inSecond = File(second, "notes.txt").also { it.writeText("data") }

        val roots = AllowedRoots.of(first, second)

        assertEquals(2, roots.rootPaths().size)
        assertEquals(inSecond.canonicalFile, roots.resolve(inSecond))
    }

    @Test
    fun `a sibling whose name starts with the root's name is refused`() {
        val parent = tempDir()
        val root = File(parent, "project").also { it.mkdirs() }
        val sibling = File(parent, "project-notes").also { it.mkdirs() }
        val file = File(sibling, "private.txt").also { it.writeText("data") }

        val roots = AllowedRoots.of(root)

        assertNull(roots.resolve(file))
        assertNotNull(roots.resolve(File(root, "own.txt")))
    }

    @Test
    fun `a symlink pointing out of a root is refused and one staying inside is not`() {
        val root = tempDir()
        val elsewhere = tempDir()
        val outsideFile = File(elsewhere, "notes.txt").also { it.writeText("data") }
        val link = symlink(File(root, "link"), elsewhere) ?: return

        val roots = AllowedRoots.of(root)

        // No ".." and no name that looks suspicious — only resolution shows where it goes.
        assertNull(roots.resolve(File(link, "notes.txt")))

        val inside = File(root, "real.txt").also { it.writeText("mine") }
        val insideLink = symlink(File(root, "alias.txt"), inside) ?: return
        assertNotNull(roots.resolve(insideLink))
        assertNull(roots.resolve(outsideFile))
    }

    @Test
    fun `a symlink whose target is missing is refused rather than followed`() {
        val root = tempDir()
        val elsewhere = tempDir()
        val missingTarget = File(elsewhere, "created-later.txt")
        val link = symlink(File(root, "pending"), missingTarget) ?: return
        assertTrue(!missingTarget.exists(), "the target must not exist for this case")

        val roots = AllowedRoots.of(root)

        assertNull(roots.resolve(link))
        assertNull(roots.resolve(File(link, "deeper.txt")))
    }

    @Test
    fun `no roots refuses everything`() {
        val root = tempDir()
        val file = File(root, "f.txt").also { it.writeText("data") }

        assertNull(AllowedRoots.none().resolve(file))
        assertEquals(emptyList(), AllowedRoots.none().rootPaths())
    }

    @Test
    fun `a root that cannot be resolved grants nothing rather than everything`() {
        val root = tempDir()
        val absent = File(root, "never-created")
        val regularFile = File(root, "f.txt").also { it.writeText("data") }

        val fromAbsent = AllowedRoots.of(absent)
        assertEquals(emptyList(), fromAbsent.rootPaths())
        assertNull(fromAbsent.resolve(File(absent, "f.txt")))

        // A file is not a directory, so it is not a usable root either.
        assertEquals(emptyList(), AllowedRoots.of(regularFile).rootPaths())
    }

    @Test
    fun `a filesystem root grants nothing because confining to it confines nothing`() {
        val filesystemRoot = File(tempDir().toPath().root.toString())

        val roots = AllowedRoots.of(filesystemRoot)

        assertEquals(emptyList(), roots.rootPaths())
        assertNull(roots.resolve(File(filesystemRoot, "etc/hosts")))
    }

    @Test
    fun `each root is reported once and in canonical form`() {
        val root = tempDir()
        val viaDot = File(root, ".")

        val roots = AllowedRoots.of(root, viaDot)

        assertEquals(listOf(root.canonicalPath), roots.rootPaths())
    }
}
