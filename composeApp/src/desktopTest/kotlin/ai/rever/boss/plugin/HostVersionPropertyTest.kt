package ai.rever.boss.plugin

import ai.rever.boss.utils.AppVersion
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The host's own version, published where a plugin can read it.
 *
 * Plugins load in separate classloaders and cannot see `AppVersion`, so anything a plugin needs to
 * know about the host arrives as a system property - the same route as `boss.api.version` and
 * `boss.ipc.version`. The app version was missing from that set, and the Toolbox is what needed it:
 * store rows carry `minBossVersion` (the Toolbox writes that field when publishing) but nothing on
 * its browse or install path could compare it against the running host, so it offered every
 * published version and Install downloaded plugins the loader then refused.
 *
 * That is how fluck-browser 1.2.22 (`minBossVersion: 9.4.23`) presented on 9.4.22: an install that
 * silently would not take, with `PluginBossVersionException` reaching only the log.
 */
class HostVersionPropertyTest {
    private fun repoRoot(): File? =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "composeApp/build.gradle.kts").isFile }

    @Test
    fun `the app version is published as a system property`() {
        // A source check, because the publish happens inside initializeApiLayer, which needs a real
        // plugin directory and an api jar to run. What matters is that the line exists next to the
        // other two, and that it publishes the same value the host filters its own catalog with.
        val root = assertNotNull(repoRoot(), "could not locate the repository root")
        val manager =
            File(
                root,
                "composeApp/src/commonMain/kotlin/ai/rever/boss/components/plugin/DynamicPluginManager.kt",
            ).readText()
        assertTrue(
            manager.contains("""System.setProperty("boss.app.version", AppVersion.currentVersionString())"""),
            "boss.app.version is not published, so no plugin can compare minBossVersion",
        )
    }

    @Test
    fun `it is published beside the other two host properties`() {
        // Ordering is not the point; being in the SAME method is. initializeApiLayer runs before any
        // plugin loads, which is what makes the value readable by the first plugin to look.
        val root = assertNotNull(repoRoot(), "could not locate the repository root")
        val text =
            File(
                root,
                "composeApp/src/commonMain/kotlin/ai/rever/boss/components/plugin/DynamicPluginManager.kt",
            ).readText()
        val init = text.substringAfter("fun initializeApiLayer(").substringBefore("\n    }")
        listOf("boss.api.version", "boss.api.jar", "boss.app.version").forEach { prop ->
            assertTrue(init.contains(prop), "$prop is not published in initializeApiLayer")
        }
    }

    @Test
    fun `the published value is a version a floor check can parse`() {
        // A blank or unparseable value fails open in every consumer that reads it, which would put
        // the Toolbox back to offering incompatible versions while looking like it checks.
        val current = AppVersion.currentVersionString()
        assertTrue(current.isNotBlank(), "the host has no version string to publish")
        assertTrue(
            Regex("""^\d+\.\d+\.\d+""").containsMatchIn(current),
            "the host version is not in a shape a floor comparison can read: $current",
        )
    }
}
