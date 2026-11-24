package ai.rever.boss.plugin

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The two filesystem guards, which were lambdas at the call site until nothing could reach them.
 *
 * Both fail silently when wrong: a mistyped id comparison disables the restore veto, and a delete
 * keyed on the wrong thing reports success while leaving a jar the directory scan will reinstall.
 */
class RetiredPluginArtifactsTest {
    @TempDir
    lateinit var pluginDir: File

    private val retired = "ai.rever.boss.plugin.dynamic.oldpanel"
    private val other = "ai.rever.boss.plugin.dynamic.otherpanel"

    private fun jar(
        name: String,
        declaring: String,
    ): File = File(pluginDir, name).apply { writeText(declaring) }

    /** The stand-in for reading a manifest: each fixture jar's body is the id it declares. */
    private val manifestIdOf: (File) -> String? = { it.readText().ifBlank { null } }

    @Test
    fun `every jar declaring the id goes, whatever it is called`() {
        // The case a path-keyed delete misses: installed.json's jarPath can be stale while a
        // differently named jar for the same plugin is still in the directory.
        val recorded = jar("boss-plugin-old-1.0.0.jar", retired)
        val stray = jar("old-panel-hotfix.jar", retired)
        val neighbour = jar("boss-plugin-other-1.0.0.jar", other)

        val clean = purgeJarsFor(retired, pluginDir, manifestIdOf)

        assertTrue(clean)
        assertFalse(recorded.exists())
        assertFalse(stray.exists(), "a second jar for the same plugin survived")
        assertTrue(neighbour.exists(), "deleted another plugin's jar")
    }

    @Test
    fun `a jar whose delete fails reports not clean`() {
        // Windows holds a lock on a jar loaded earlier in the process and delete() returns false
        // silently, which is why the answer is re-listed rather than taken from the delete result.
        jar("boss-plugin-old-1.0.0.jar", retired)

        val clean = purgeJarsFor(retired, pluginDir, manifestIdOf, deleteJar = { false })

        assertFalse(clean, "a surviving jar was reported as removed")
    }

    @Test
    fun `nothing to delete is success`() {
        // Absence is the postcondition, not the delete count.
        jar("boss-plugin-other-1.0.0.jar", other)

        assertTrue(purgeJarsFor(retired, pluginDir, manifestIdOf))
    }

    @Test
    fun `a missing plugin directory is not a crash`() {
        assertTrue(purgeJarsFor(retired, File(pluginDir, "gone"), manifestIdOf))
    }

    @Test
    fun `the sidecar goes with the jar`() {
        // Reinstalling the same version reuses the filename, so a surviving signature meets fresh
        // bytes and hard-fails that load - worse than being unsigned.
        val target = jar("boss-plugin-old-1.0.0.jar", retired)
        val sidecarsDeleted = mutableListOf<String>()

        purgeJarsFor(retired, pluginDir, manifestIdOf, deleteSidecar = { sidecarsDeleted += it.name })

        assertEquals(listOf(target.name), sidecarsDeleted)
    }

    @Test
    fun `a bundled plugin is vetoed, and its reason is passed through`() {
        assertEquals(
            "ships with BOSS and would be restored at the next launch",
            restoredAtNextLaunchReason(
                pluginId = retired,
                bundledVeto = "ships with BOSS and would be restored at the next launch",
                systemPluginIds = emptySet(),
            ),
        )
    }

    @Test
    fun `a system plugin is vetoed`() {
        assertEquals(
            "is a system plugin and would be reinstalled at the next launch",
            restoredAtNextLaunchReason(retired, bundledVeto = null, systemPluginIds = setOf(retired)),
        )
    }

    @Test
    fun `an ordinary plugin is not vetoed`() {
        assertEquals(
            null,
            restoredAtNextLaunchReason(retired, bundledVeto = null, systemPluginIds = setOf(other)),
        )
    }
}
