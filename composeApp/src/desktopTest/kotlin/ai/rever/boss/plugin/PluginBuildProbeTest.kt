package ai.rever.boss.plugin

import ai.rever.boss.components.plugin.PluginBuildInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers what the tag actually claims, because every part of it is a claim that can be wrong in a way
 * nothing else would notice: a released build wrongly tagged teaches the user to ignore the tag, and
 * a local build left untagged is the situation this feature exists to end.
 */
class PluginBuildProbeTest {
    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
        const val JAR = "/Users/someone/.boss/plugins/probe-1.0.3.jar"
        const val VERSION = "1.0.3"
        const val FIRST_LOAD = 1_754_800_000_000L
        const val REBUILT = 1_754_890_231_447L
    }

    /** What the probe wrote down, as a named shape so the assertions read as claims. */
    private data class Recorded(
        val jarPath: String,
        val stamp: Long?,
        val tag: String?,
    )

    private val recorded = mutableListOf<Recorded>()

    private fun probe(
        storeVetted: Boolean,
        mtime: Long?,
        previous: RecordedBuild?,
        systemPlugin: Boolean = false,
    ): PluginBuildInfo =
        PluginBuildProbe.probe(
            plugin = ProbedPlugin(PLUGIN, "Probe", VERSION, JAR, systemPlugin),
            hooks =
                BuildProbeHooks(
                    mtimeOf = { mtime },
                    sidecarPresent = { storeVetted },
                    recordedBuild = { previous },
                    record = { _, jarPath, stamp, tag, _ -> recorded.add(Recorded(jarPath, stamp, tag)) },
                ),
        )

    @Test
    fun `a store-signed jar carries no tag and its version is untouched`() {
        val info = probe(storeVetted = true, mtime = FIRST_LOAD, previous = null)

        assertNull(info.tagLabel)
        assertEquals(VERSION, info.displayVersion, "a released build must read as plain semver")
        assertEquals(listOf(Recorded(JAR, FIRST_LOAD, null)), recorded)
    }

    @Test
    fun `an unsigned jar reads as a debug build`() {
        val info = probe(storeVetted = false, mtime = FIRST_LOAD, previous = null)

        assertEquals("DEBUG", info.tagLabel)
        assertEquals("$VERSION-debug", info.displayVersion)
        assertEquals(listOf(Recorded(JAR, FIRST_LOAD, PluginBuildProbe.TAG_DEBUG)), recorded)
    }

    @Test
    fun `bytes replaced in place read as hot reloaded, stamped with the new mtime`() {
        // The evolver's move: the same path, newer bytes, the same manifest version. Without the
        // stamp, two iterations of a build are indistinguishable.
        val info =
            probe(
                storeVetted = false,
                mtime = REBUILT,
                previous = RecordedBuild(JAR, buildStamp = FIRST_LOAD, buildTag = PluginBuildProbe.TAG_DEBUG),
            )

        assertEquals("HOT", info.tagLabel)
        assertEquals("$VERSION-debug+$REBUILT", info.displayVersion)
        assertEquals(listOf(Recorded(JAR, REBUILT, PluginBuildProbe.TAG_HOT)), recorded)
    }

    @Test
    fun `a hot reload is still hot after a restart`() {
        // The restart case: nothing modified the file since, so the mtime comparison alone would say
        // "fresh install" and the tag would silently disappear on relaunch. The recorded tag is what
        // keeps it honest, which is the whole reason the verdict is persisted.
        val info =
            probe(
                storeVetted = false,
                mtime = REBUILT,
                previous = RecordedBuild(JAR, buildStamp = REBUILT, buildTag = PluginBuildProbe.TAG_HOT),
            )

        assertEquals("HOT", info.tagLabel)
        assertEquals("$VERSION-debug+$REBUILT", info.displayVersion)
    }

    @Test
    fun `a row for a different jar says nothing about these bytes`() {
        // A store update installs a new versioned filename. Carrying the old row's stamp over would
        // report the fresh download as a hot reload.
        val info =
            probe(
                storeVetted = false,
                mtime = REBUILT,
                previous = RecordedBuild("/elsewhere/probe-1.0.2.jar", FIRST_LOAD, PluginBuildProbe.TAG_HOT),
            )

        assertEquals("DEBUG", info.tagLabel, "different file, so only the missing signature counts")
        assertEquals("$VERSION-debug", info.displayVersion)
    }

    @Test
    fun `a store-signed jar reinstalled over its own filename is not a hot reload`() {
        // Reinstalling the same version reuses the name, so the mtime moves. Only the signature can
        // settle it, and it does: a locally rebuilt jar cannot carry a valid one.
        assertNull(
            PluginBuildProbe.resolveReloadStamp(
                signedBytes = true,
                jarMtime = REBUILT,
                recordedStamp = FIRST_LOAD,
                recordedTag = PluginBuildProbe.TAG_DEBUG,
            ),
        )
    }

    @Test
    fun `an unreadable mtime degrades to the signature verdict rather than guessing`() {
        val info =
            probe(
                storeVetted = false,
                mtime = null,
                previous = RecordedBuild(JAR, buildStamp = FIRST_LOAD, buildTag = PluginBuildProbe.TAG_DEBUG),
            )

        assertEquals("DEBUG", info.tagLabel)
        // The previously recorded stamp is kept rather than being nulled out by an unreadable file.
        assertEquals(listOf(Recorded(JAR, FIRST_LOAD, PluginBuildProbe.TAG_DEBUG)), recorded)
    }

    @Test
    fun `an unsigned jar that the store installed is not a local build`() {
        // The signature rollout is still open: PluginSignatureEnforcement defaults to off, an
        // unsigned store row warns-and-allows, and persist(null) DELETES the sidecar - so a genuine
        // store install can arrive with no sidecar at all. Reading that as "local build" put a DEBUG
        // tag on released plugins, which is the failure that teaches a user to ignore the tag.
        val info =
            probe(
                storeVetted = false,
                mtime = FIRST_LOAD,
                previous = RecordedBuild(JAR, null, null, sourceUrl = "https://store.example/probe.jar"),
            )

        assertNull(info.tagLabel)
        assertEquals(VERSION, info.displayVersion)
    }

    @Test
    fun `a system plugin is never tagged`() {
        // Its sidecar is backfilled from the store row asynchronously, so on the launch where that
        // backfill happens it would otherwise be tagged DEBUG and have "debug" persisted.
        val info = probe(storeVetted = false, mtime = FIRST_LOAD, previous = null, systemPlugin = true)

        assertNull(info.tagLabel)
    }

    @Test
    fun `a store-installed plugin whose bytes were replaced is still reported as hot`() {
        // The store origin says where the plugin came from; it must not vouch for what is in the file
        // now, or overwriting a store jar in place would be the one hot reload that goes unreported.
        val info =
            probe(
                storeVetted = false,
                mtime = REBUILT,
                previous =
                    RecordedBuild(JAR, FIRST_LOAD, null, sourceUrl = "https://store.example/probe.jar"),
            )

        assertEquals("HOT", info.tagLabel)
        // Not "-debug": nothing suggests this plugin was never published, only that it was replaced.
        assertEquals("$VERSION+$REBUILT", info.displayVersion)
    }

    @Test
    fun `the suffix never reaches the canonical version`() {
        // installedVersion feeds isNewerVersion and the pluginId|version|sha256 signing anchor, so a
        // suffixed string landing there would break update checks and signature verification.
        val info =
            probe(
                storeVetted = false,
                mtime = REBUILT,
                previous = RecordedBuild(JAR, FIRST_LOAD, PluginBuildProbe.TAG_DEBUG),
            )

        assertEquals(VERSION, info.version)
        assertTrue(info.displayVersion.startsWith(VERSION))
    }
}
