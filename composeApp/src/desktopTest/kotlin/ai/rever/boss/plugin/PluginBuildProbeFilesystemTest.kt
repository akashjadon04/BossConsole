package ai.rever.boss.plugin

import ai.rever.boss.plugin.loader.PluginSignatureSidecar
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Exercises the probe's two real signals against real files.
 *
 * `PluginBuildProbeTest` covers the verdict logic with every signal injected, which means it would
 * still pass if the production defaults read the wrong thing entirely - a sidecar looked up under the
 * wrong name, or an mtime that never moves. Only the filesystem can settle that, so this test hands
 * the probe actual jars on disk and stubs nothing but the `installed.json` access (which resolves
 * through `PluginStoreSetup.getPluginDir()` and would rewrite the developer's own config).
 */
class PluginBuildProbeFilesystemTest {
    /**
     * JUnit 5's, not JUnit 4's `TemporaryFolder`: this module runs on the JUnit Platform with
     * `kotlin("test-junit5")`, so a `@Rule` on a `kotlin.test.Test` class is silently ignored and the
     * folder is never created.
     */
    @TempDir
    lateinit var temp: File

    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
        const val VERSION = "1.0.3"
    }

    private var written: RecordedBuild? = null

    /** Only the persistence is stubbed; mtime and sidecar reading are the production defaults. */
    private fun probe(previous: RecordedBuild?) =
        PluginBuildProbe.probe(
            plugin = ProbedPlugin(PLUGIN, "Probe", VERSION, jar.absolutePath),
            hooks =
                BuildProbeHooks(
                    recordedBuild = { previous },
                    record = { _, jarPath, stamp, tag, _ -> written = RecordedBuild(jarPath, stamp, tag) },
                ),
        )

    private val jar by lazy { File(temp, "probe-$VERSION.jar").apply { writeText("not really a jar") } }

    @Test
    fun `a jar with no sidecar on disk reads as a local build`() {
        val info = probe(previous = null)

        assertEquals("DEBUG", info.tagLabel)
        assertEquals("$VERSION-debug", info.displayVersion)
        // The real mtime was read, not a zero or a guess.
        assertEquals(jar.lastModified(), written?.buildStamp)
    }

    @Test
    fun `a jar with a sidecar beside it reads as a released build`() {
        // Content is irrelevant here: the load-time gate is what verifies a signature, and it
        // hard-fails an invalid one - so on a plugin that is running, presence is the signal. What
        // this pins is that the probe looks for the sidecar where the sidecar actually is.
        PluginSignatureSidecar.persist(jar.absolutePath, "c2lnbmF0dXJl")
        try {
            val info = probe(previous = null)

            assertNull(info.tagLabel, "a store-signed jar must carry no tag")
            assertEquals(VERSION, info.displayVersion)
            assertNull(written?.buildTag)
        } finally {
            PluginSignatureSidecar.delete(jar.absolutePath)
        }
    }

    @Test
    fun `overwriting the jar in place is detected as a hot reload`() {
        // The evolver's move, performed for real: same path, new bytes, later mtime.
        val firstLoad = probe(previous = null)
        assertEquals("DEBUG", firstLoad.tagLabel)
        val firstStamp = assertNotNull(written?.buildStamp)

        // A filesystem's mtime granularity can be as coarse as a second, so the rewrite is stamped
        // explicitly rather than by sleeping. This is what an evolver copy does to the file.
        jar.writeText("a newer build")
        jar.setLastModified(firstStamp + 5_000)

        val afterReload = probe(previous = RecordedBuild(jar.absolutePath, firstStamp, PluginBuildProbe.TAG_DEBUG))

        assertEquals("HOT", afterReload.tagLabel)
        assertEquals("$VERSION-debug+${firstStamp + 5_000}", afterReload.displayVersion)
        assertEquals(PluginBuildProbe.TAG_HOT, written?.buildTag)
    }

    @Test
    fun `an untouched jar loaded twice does not become a hot reload`() {
        // Guards the other direction: every ordinary restart re-runs the probe, and a comparison that
        // was off by one would tag the whole fleet.
        probe(previous = null)
        val stamp = assertNotNull(written?.buildStamp)

        val second = probe(previous = RecordedBuild(jar.absolutePath, stamp, PluginBuildProbe.TAG_DEBUG))

        assertEquals("DEBUG", second.tagLabel)
        assertNull(second.reloadStamp)
    }

    @Test
    fun `a jar that is not there degrades instead of throwing`() {
        val info =
            PluginBuildProbe.probe(
                plugin = ProbedPlugin(PLUGIN, "Probe", VERSION, File(temp, "absent.jar").absolutePath),
                hooks = BuildProbeHooks(recordedBuild = { null }, record = { _, _, _, _, _ -> }),
            )

        // Unsigned by absence, and no crash: the probe runs inside every install.
        assertEquals("DEBUG", info.tagLabel)
        assertNull(info.reloadStamp)
    }
}
