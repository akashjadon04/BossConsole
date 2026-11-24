package ai.rever.boss.project

import ai.rever.boss.window.Project
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guards standing between a checkbox and someone's files.
 *
 * The cross on a project card used to call `removeRecentProject` and nothing else. It now
 * offers to move the folder to the Trash as well, which is the only thing BOSS does to a
 * directory the user has not otherwise pointed it at - so the question "which paths must
 * this refuse" is worth holding down rather than reading once.
 *
 * These exercise [pathTrashRefusal], not [trashRefusal]: the latter asks AWT whether this
 * desktop has a Trash at all, and a headless JVM says no, which would make every case
 * below pass for the wrong reason.
 */
class ProjectRemovalTest {
    private val temps = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        temps.forEach { it.deleteRecursively() }
    }

    private fun tempDir(): File =
        File.createTempFile("project-removal", "").let { file ->
            file.delete()
            file.mkdirs()
            temps += file
            file
        }

    @Test
    fun `an ordinary project folder may be trashed`() {
        assertNull(pathTrashRefusal(tempDir().absolutePath))
    }

    @Test
    fun `a blank path is refused`() {
        assertNotNull(pathTrashRefusal(""))
        assertNotNull(pathTrashRefusal("   "))
    }

    @Test
    fun `a folder that is already gone is refused`() {
        val gone = File(tempDir(), "never-existed")
        assertNotNull(pathTrashRefusal(gone.absolutePath))
    }

    /** A recent-projects entry is a directory. A file there means something else is wrong. */
    @Test
    fun `a plain file is refused`() {
        val file = File(tempDir(), "notes.txt").apply { writeText("x") }
        assertNotNull(pathTrashRefusal(file.absolutePath))
    }

    @Test
    fun `the home folder is refused`() {
        val home = System.getProperty("user.home")
        assertNotNull(pathTrashRefusal(home), "the home directory must never be trashable")
    }

    /**
     * The guard is on the canonical path, so the ways of naming `$HOME` without spelling it
     * are caught too. A `..` walk is the one someone actually reaches by accident.
     */
    @Test
    fun `a path that resolves to the home folder is refused`() {
        val home = File(System.getProperty("user.home"))
        val roundabout = File(home, "..").resolve(home.name).path
        assertNotNull(pathTrashRefusal(roundabout))
    }

    @Test
    fun `a filesystem root is refused`() {
        File.listRoots().forEach { root ->
            assertNotNull(pathTrashRefusal(root.path), "root ${root.path} must never be trashable")
        }
    }

    /**
     * The reason is shown to the user under a disabled checkbox, so it has to read as a
     * sentence rather than as an enum name.
     */
    @Test
    fun `every refusal is a readable sentence`() {
        val refusals =
            listOfNotNull(
                pathTrashRefusal(""),
                pathTrashRefusal(File(tempDir(), "gone").absolutePath),
                pathTrashRefusal(System.getProperty("user.home")),
            )
        assertEquals(3, refusals.size)
        refusals.forEach { reason ->
            assertTrue(reason.endsWith("."), "not a sentence: $reason")
            assertTrue(reason.first().isUpperCase(), "not a sentence: $reason")
        }
    }

    private val project = Project(name = "Boss", path = "/tmp/boss", lastOpened = 0L)

    @Test
    fun `forgetting a project says only that`() {
        assertEquals(
            "Removed Boss from BOSS",
            ProjectRemovalResult.Removed(trashed = false).describe(project),
        )
    }

    @Test
    fun `trashing the folder says so too`() {
        assertEquals(
            "Removed Boss and moved its folder to the Trash",
            ProjectRemovalResult.Removed(trashed = true).describe(project),
        )
    }

    /**
     * The half-done case, and the reason the result type is not a Boolean: the entry went
     * and the folder did not, and a message reporting only the failure would read as though
     * nothing had happened at all.
     */
    @Test
    fun `a kept folder reports both halves`() {
        val message =
            ProjectRemovalResult.FolderKept("That folder is already gone.").describe(project)
        assertTrue(message.contains("Removed Boss from BOSS"), message)
        assertTrue(message.contains("still there"), message)
        assertTrue(message.contains("That folder is already gone."), message)
    }
}
