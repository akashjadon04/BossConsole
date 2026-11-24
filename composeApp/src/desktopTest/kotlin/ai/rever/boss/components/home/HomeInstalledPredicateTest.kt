package ai.rever.boss.components.home

import ai.rever.boss.components.plugin.DynamicPluginInfo
import ai.rever.boss.components.plugin.PluginDependencyResolution
import ai.rever.boss.plugin.api.PluginManifest
import ai.rever.boss.plugin.api.PluginState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What "installed" must mean for the tool grid.
 *
 * `rememberHomeTools` is a composable, so the predicate it passes to `installedAndOnDisk` is not
 * directly reachable from a unit test. These pin the predicate's behaviour on the inputs that
 * matter, which is the part that was wrong: the grid originally passed `exists = { true }` and
 * omitted `isIncompatible`, reducing the whole thing to `LOADED || true` - every entry in
 * `pluginStates`, whatever its state.
 *
 * The consequence was invisible. A plugin whose install failed as binary-incompatible keeps a
 * DISABLED entry while the installer deletes its jar. It counted as installed, so its store row was
 * filtered out of the discovery half, and having registered no tab type or panel it had no ready
 * tile either. It disappeared from the grid entirely, with no way to retry the install from the
 * surface that exists to offer it.
 */
class HomeInstalledPredicateTest {
    private fun info(
        pluginId: String,
        state: PluginState,
        jarPath: String = "/plugins/$pluginId.jar",
    ) = DynamicPluginInfo(
        manifest =
            PluginManifest(
                pluginId = pluginId,
                displayName = pluginId,
                version = "1.0.0",
                mainClass = "$pluginId.Main",
                apiVersion = "1.0.0",
            ),
        jarPath = jarPath,
        state = state,
        loadedAt = 0L,
        enabled = true,
    )

    /** The predicate as the grid now supplies it: a real jar probe plus the incompatibility flag. */
    private fun installedIds(
        states: Map<String, DynamicPluginInfo>,
        onDisk: Set<String> = states.values.map { it.jarPath }.toSet(),
        incompatible: Set<String> = emptySet(),
    ) = PluginDependencyResolution.installedAndOnDisk(
        states = states,
        exists = { it in onDisk },
        isIncompatible = { it in incompatible },
    )

    @Test
    fun `a binary-incompatible plugin whose jar was deleted does not count as installed`() {
        // The case the grid got wrong. It must be offerable again, not hidden.
        val broken = "ai.rever.boss.plugin.dynamic.broken"
        val states = mapOf(broken to info(broken, PluginState.DISABLED))

        val installed =
            installedIds(
                states = states,
                onDisk = emptySet(),
                incompatible = setOf(broken),
            )

        assertFalse(
            broken in installed,
            "A DISABLED entry with no jar must not count as installed, or the plugin vanishes " +
                "from the grid with no way to retry",
        )
    }

    @Test
    fun `a loaded plugin counts even when its jar path has moved`() {
        // PluginJarReconciler and the updater rewrite paths without repointing the manager's
        // in-memory jarPath, so a running plugin can hold a path that no longer exists. Treating
        // it as absent would offer an install that fails with "Plugin already loaded".
        val arcade = "ai.rever.boss.plugin.dynamic.arcade"
        val states = mapOf(arcade to info(arcade, PluginState.LOADED))

        val installed = installedIds(states = states, onDisk = emptySet())

        assertTrue(arcade in installed)
    }

    @Test
    fun `a disabled plugin with its jar still present counts as installed`() {
        // The user disabled it; it is installed, so the grid must not offer to install it again.
        val docker = "ai.rever.boss.plugin.dynamic.docker"
        val states = mapOf(docker to info(docker, PluginState.DISABLED))

        assertTrue(docker in installedIds(states))
    }

    @Test
    fun `the weakened predicate the grid used to pass would have called everything installed`() {
        // Guards the reasoning rather than the code: this is what `exists = { true }` with no
        // isIncompatible produced, and it is why the bug was total rather than partial.
        val states =
            mapOf(
                "a" to info("a", PluginState.DISABLED),
                "b" to info("b", PluginState.ERROR),
                "c" to info("c", PluginState.UNLOADED),
            )

        val weakened =
            PluginDependencyResolution.installedAndOnDisk(
                states = states,
                exists = { true },
            )

        assertTrue(
            weakened.size == states.size,
            "If this ever stops being true the predicate changed shape and the comment in " +
                "rememberHomeTools explaining the old bug needs revisiting",
        )
        // And with the predicate the grid passes now, none of the three is installed.
        assertTrue(installedIds(states, onDisk = emptySet()).isEmpty())
    }
}
