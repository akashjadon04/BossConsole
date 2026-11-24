package ai.rever.boss.plugin

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers what an uninstall leaves behind, and what it should refuse to attempt.
 *
 * Every one of these is a silent failure if it goes wrong: a surviving row makes the plugin come back
 * at the next launch, a surviving sidecar hard-fails the next download that reuses the filename, and
 * a bundled plugin that "uninstalls" successfully and returns anyway is worse than being told no.
 */
class PluginRemovalTest {
    @TempDir
    lateinit var dir: File

    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
    }

    private val deletedSidecars = mutableListOf<String>()
    private val forgotten = mutableListOf<String>()

    private fun hooks(deleteJarResult: (String) -> Boolean = { File(it).delete() }) =
        PluginArtifactCleanup.Hooks(
            deleteJar = deleteJarResult,
            deleteSidecar = { deletedSidecars += it },
            forgetRow = { forgotten += it },
        )

    @Test
    fun `removal deletes the jar, its sidecar and the installed row`() {
        val jar = File(dir, "probe-1.0.0.jar").apply { writeText("jar") }

        PluginArtifactCleanup.remove(PLUGIN, jar.absolutePath, hooks())

        assertTrue(!jar.exists())
        assertEquals(listOf(jar.absolutePath), deletedSidecars)
        // The row was the piece nothing removed before this feature: removeInstalledPlugin had zero
        // production callers, so every uninstall left a row pointing at a deleted file.
        assertEquals(listOf(PLUGIN), forgotten)
    }

    @Test
    fun `the row is forgotten even when the jar is already gone`() {
        // The jar can vanish first - a reconciler sweep, a manual delete, a failed install that
        // discarded it. The row still has to go, or the persisted-load pass retries it every launch.
        PluginArtifactCleanup.remove(PLUGIN, File(dir, "absent.jar").absolutePath, hooks { false })

        assertEquals(listOf(PLUGIN), forgotten)
    }

    @Test
    fun `a blank jar path still forgets the row and touches no files`() {
        PluginArtifactCleanup.remove(PLUGIN, "", hooks { error("must not try to delete a blank path") })

        assertEquals(emptyList<String>(), deletedSidecars)
        assertEquals(listOf(PLUGIN), forgotten)
    }

    @Test
    fun `a bundled plugin is vetoed rather than uninstalled and quietly restored`() {
        // copyBundledPluginsToPluginDir re-copies a bundled jar whenever no jar for its id is in the
        // plugins directory, so uninstalling one succeeds and then undoes itself at the next launch.
        File(dir, "bundled.jar").writeText("jar")

        val veto = PluginRemoval.removalVeto(PLUGIN, dir, readManifestId = { PLUGIN })

        assertNotNull(veto)
        assertTrue(veto.contains("restored"), "the reason should say what will happen: $veto")
    }

    @Test
    fun `a plugin that is not bundled is not vetoed`() {
        File(dir, "bundled.jar").writeText("jar")

        assertNull(PluginRemoval.removalVeto(PLUGIN, dir, readManifestId = { "some.other.plugin" }))
    }

    @Test
    fun `an absent bundled directory vetoes nothing`() {
        // The ordinary dev case: no bundled-plugins directory at all. It must not block uninstalls.
        assertNull(PluginRemoval.removalVeto(PLUGIN, File(dir, "nope"), readManifestId = { PLUGIN }))
    }

    @Test
    fun `an unreadable bundled jar does not veto by accident`() {
        File(dir, "corrupt.jar").writeText("not a jar")

        assertNull(PluginRemoval.removalVeto(PLUGIN, dir, readManifestId = { null }))
    }
}
