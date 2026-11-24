package ai.rever.boss.components.plugin

import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Cleanup after a successful signature reinstall.
 *
 * Tested against a real temp directory rather than a fake, because every interesting case here is
 * about which FILES survive - and the identification is by manifest, not by filename, so a fake
 * that hands back names would not exercise the part that can be wrong.
 *
 * The failure this guards is specific: the reinstall writes a version-named jar, so the refused one
 * can survive under a different name. Two jars then declare one pluginId and the startup scan can
 * pick either - which means the next launch could come back refused with nothing on screen to
 * explain why, the exact silence the gate exists to remove.
 */
class DropRefusedArtifactsTest {
    private val pluginId = "ai.rever.boss.plugin.dynamic.pluginmanager"

    /** A jar carrying just enough manifest for [ai.rever.boss.plugin.loader.PluginManifestReader]. */
    private fun jar(
        dir: File,
        name: String,
        declaring: String = pluginId,
        version: String = "1.9.20",
    ): File {
        val file = File(dir, name)
        JarOutputStream(file.outputStream()).use { out ->
            out.putNextEntry(JarEntry("META-INF/boss-plugin/plugin.json"))
            out.write(
                """
                {
                  "manifestVersion": 1,
                  "pluginId": "$declaring",
                  "displayName": "Toolbox",
                  "version": "$version",
                  "apiVersion": "1.0.0",
                  "mainClass": "x.Y"
                }
                """.trimIndent().toByteArray(),
            )
            out.closeEntry()
        }
        return file
    }

    private fun sidecar(jar: File): File = File("${jar.absolutePath}.sig").apply { writeText("not-a-real-signature") }

    private fun tempDir(): File =
        File.createTempFile("plugins", "").let { probe ->
            probe.delete()
            probe.mkdirs()
            probe
        }

    @Test
    fun `the refused jar and its sidecar go, the kept one stays`() {
        val dir = tempDir()
        val refused = jar(dir, "boss-plugin-plugin-manager-1.9.20.jar")
        val refusedSig = sidecar(refused)
        val fresh = jar(dir, "ai_rever_boss_plugin_dynamic_pluginmanager_1.9.21.jar", version = "1.9.21")
        val freshSig = sidecar(fresh)

        dropRefusedArtifacts(dir, pluginId, keep = fresh.absolutePath)

        assertFalse(refused.exists(), "the refused jar survived and the next scan could pick it")
        assertFalse(refusedSig.exists(), "a sidecar with no jar is inherited by a later same-name install")
        assertTrue(fresh.exists(), "deleted the jar the plugin is loaded from")
        assertTrue(freshSig.exists(), "deleted the signature of the jar that is loaded")
    }

    @Test
    fun `nothing is deleted when the kept jar is unknown`() {
        // The install reported success but the manager has no record of which jar is live. Deleting
        // anything here is a guess, and the wrong guess removes a working plugin.
        val dir = tempDir()
        val a = jar(dir, "boss-plugin-plugin-manager-1.9.20.jar")
        val b = jar(dir, "ai_rever_boss_plugin_dynamic_pluginmanager_1.9.21.jar", version = "1.9.21")

        dropRefusedArtifacts(dir, pluginId, keep = null)

        assertTrue(a.exists() && b.exists(), "deleted files without knowing which one was live")
    }

    @Test
    fun `other plugins are untouched`() {
        // Identification is by the manifest inside the jar. A filename-based sweep would be a
        // prefix match, and `boss-plugin-terminal-tab` shares one with nothing here only by luck.
        val dir = tempDir()
        val mine = jar(dir, "boss-plugin-plugin-manager-1.9.20.jar")
        val theirs =
            jar(
                dir,
                "boss-plugin-terminal-tab-2.5.59.jar",
                declaring = "ai.rever.boss.plugin.dynamic.terminaltab",
            )
        val theirsSig = sidecar(theirs)
        val keep = jar(dir, "ai_rever_boss_plugin_dynamic_pluginmanager_1.9.21.jar", version = "1.9.21")

        dropRefusedArtifacts(dir, pluginId, keep = keep.absolutePath)

        assertFalse(mine.exists())
        assertTrue(theirs.exists(), "removed another plugin's jar")
        assertTrue(theirsSig.exists(), "removed another plugin's signature")
    }

    @Test
    fun `unreadable jars and non-jar files are left alone`() {
        // A `.part` from an interrupted download and a rollback directory both live here. Neither
        // has a readable manifest, and deleting either would be collateral damage.
        val dir = tempDir()
        val garbage = File(dir, "half-written.jar").apply { writeText("not a zip") }
        val part = File(dir, "something.jar.part").apply { writeText("partial") }
        val rollback = File(dir, ".rollback").apply { mkdirs() }
        val keep = jar(dir, "ai_rever_boss_plugin_dynamic_pluginmanager_1.9.21.jar", version = "1.9.21")

        dropRefusedArtifacts(dir, pluginId, keep = keep.absolutePath)

        assertTrue(garbage.exists(), "deleted a jar whose manifest could not be read")
        assertTrue(part.exists(), "deleted a partial download")
        assertTrue(rollback.isDirectory, "deleted the rollback directory")
        assertTrue(keep.exists())
    }

    @Test
    fun `a refused jar with no sidecar is still removed`() {
        // The sidecar is optional: an unsigned local build has none, and a mismatched one is the
        // motivating case. Neither may leave the jar behind.
        val dir = tempDir()
        val refused = jar(dir, "boss-plugin-plugin-manager-1.9.20.jar")
        val keep = jar(dir, "ai_rever_boss_plugin_dynamic_pluginmanager_1.9.21.jar", version = "1.9.21")

        dropRefusedArtifacts(dir, pluginId, keep = keep.absolutePath)

        assertFalse(refused.exists(), "a jar with no sidecar was left behind")
        assertTrue(keep.exists())
    }
}
