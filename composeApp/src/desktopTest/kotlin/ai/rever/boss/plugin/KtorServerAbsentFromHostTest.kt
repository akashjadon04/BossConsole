package ai.rever.boss.plugin

import java.io.File
import java.util.zip.ZipFile
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The host must not ship a ktor SERVER stack.
 *
 * BossConsole declares ktor client only; ktor-server-cio and ktor-server-core
 * used to arrive transitively through supabase auth-kt, whose only consumer of
 * them is the localhost OAuth/SSO HTTP callback BOSS never takes. They are
 * excluded in composeApp/build.gradle.kts, because 529 host-owned
 * io.ktor.server.* classes are exactly the material a plugin classloader's
 * parent fallback turns into a loader-constraint LinkageError — the failure
 * mode a terminal-tab hot-reload hit on io/ktor/util/AttributeKey.
 *
 * Gradle has no wildcard for module names, so the exclusion in the build file
 * is an enumeration and would not notice a future ktor-server-sse /
 * -websockets / -netty arriving by a new transitive path. This test is the
 * drift guard: it scans the whole classpath rather than probing known class
 * names, so anything carrying io/ktor/server/ fails here and names the artifact
 * to add.
 */
class KtorServerAbsentFromHostTest {
    private fun classpathEntries(): List<File> =
        System
            .getProperty("java.class.path")
            .orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .map(::File)

    private fun carriesKtorServer(entry: File): Boolean =
        when {
            entry.isDirectory -> {
                File(entry, "io/ktor/server").isDirectory
            }

            entry.isFile && entry.name.endsWith(".jar") -> {
                runCatching {
                    ZipFile(entry).use { zip ->
                        zip.entries().asSequence().any {
                            it.name.startsWith("io/ktor/server/") && it.name.endsWith(".class")
                        }
                    }
                }.getOrDefault(false)
            }

            else -> {
                false
            }
        }

    @Test
    fun `nothing on the host classpath carries io ktor server classes`() {
        val offenders = classpathEntries().filter(::carriesKtorServer).map { it.name }

        assertTrue(
            offenders.isEmpty(),
            "ktor-server is back on the host classpath via ${offenders.joinToString()}. Find the " +
                "new transitive path with `./gradlew :composeApp:dependencyInsight --configuration " +
                "desktopRuntimeClasspath --dependency <artifact>` and add it to the exclusion block " +
                "in composeApp/build.gradle.kts - or, if the host genuinely needs a ktor server " +
                "now, delete that block and this test together.",
        )
    }

    @Test
    fun `the ktor client stack the app actually uses is still present`() {
        // The other half of the assertion: the exclusion must not have taken the
        // client engine (supabase, the plugin store and the updater all ride on
        // it) with it. initialize=false — this is a classpath assertion and has
        // no business running static initializers.
        for (name in listOf(
            "io.ktor.client.HttpClient",
            "io.ktor.client.engine.cio.CIOEngine",
            "io.ktor.util.AttributeKey",
        )) {
            assertNotNull(
                Class.forName(name, false, javaClass.classLoader),
                "$name must stay on the host classpath",
            )
        }
    }
}
