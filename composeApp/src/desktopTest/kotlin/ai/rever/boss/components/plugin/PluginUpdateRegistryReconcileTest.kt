package ai.rever.boss.components.plugin

import org.junit.jupiter.api.AfterEach
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The badge has to go away when the update happens, whoever applied it.
 *
 * Before this, the registry was cleared only by the host's own update path, so a
 * plugin updated from the Toolbox panel or from its update toast kept showing an
 * "update available" badge - and the prompt that badge opens offered to install a
 * version that was already running.
 */
class PluginUpdateRegistryReconcileTest {
    @AfterEach
    fun clean() {
        PluginUpdateRegistry.updates.value.keys
            .toList()
            .forEach(PluginUpdateRegistry::clear)
    }

    private fun publish(
        pluginId: String,
        current: String,
        new: String,
    ) = PluginUpdateRegistry.put(AvailablePluginUpdate(pluginId, pluginId, current, new))

    @Test
    fun `an entry clears once the plugin is no longer at the version it was found on`() {
        publish("a", current = "1.0.0", new = "1.1.0")

        PluginUpdateRegistry.reconcile(mapOf("a" to "1.1.0"))

        assertNull(PluginUpdateRegistry.updates.value["a"])
    }

    @Test
    fun `a plugin still on the checked version keeps its badge`() {
        publish("a", current = "1.0.0", new = "1.1.0")

        PluginUpdateRegistry.reconcile(mapOf("a" to "1.0.0"))

        assertNotNull(PluginUpdateRegistry.updates.value["a"])
    }

    @Test
    fun `a downgrade invalidates the entry too`() {
        // Comparing against currentVersion rather than newVersion means no semver
        // ordering is needed: what makes the entry stale is the plugin having moved.
        publish("a", current = "1.0.0", new = "1.1.0")

        PluginUpdateRegistry.reconcile(mapOf("a" to "0.9.0"))

        assertNull(PluginUpdateRegistry.updates.value["a"])
    }

    @Test
    fun `plugins absent from the snapshot are left alone`() {
        publish("a", current = "1.0.0", new = "1.1.0")

        // Every plugin is unloaded during an api hot swap. Treating that as
        // "updated" would wipe every badge in the app.
        PluginUpdateRegistry.reconcile(emptyMap())
        assertNotNull(PluginUpdateRegistry.updates.value["a"])

        PluginUpdateRegistry.reconcile(mapOf("b" to "2.0.0"))
        assertNotNull(PluginUpdateRegistry.updates.value["a"])
    }

    @Test
    fun `other plugins are untouched`() {
        publish("a", current = "1.0.0", new = "1.1.0")
        publish("b", current = "2.0.0", new = "2.1.0")

        PluginUpdateRegistry.reconcile(mapOf("a" to "1.1.0", "b" to "2.0.0"))

        assertEquals(setOf("b"), PluginUpdateRegistry.updates.value.keys)
    }
}
