package ai.rever.boss.components.plugin

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [MissingHandlerPluginBus].
 *
 * Its own class rather than the singleton, for the reason
 * [PluginDependencyBus]'s KDoc gives: the shared buffer otherwise carries prompts
 * between tests, which is the same coupling two windows would have.
 */
class MissingHandlerPluginBusTest {
    private fun bus() = MissingHandlerPluginBus()

    private fun prompt(
        pluginId: String,
        remedy: MissingHandlerRemedy = MissingHandlerRemedy.INSTALL,
        purpose: String = "Opening README.md",
    ) = MissingHandlerPluginPrompt(
        missing =
            MissingHandlerPlugin(
                purpose = purpose,
                capability = "files in the editor",
                tabTypeId = "editor",
                pluginId = pluginId,
                remedy = remedy,
            ),
        resolve = { Result.success(Unit) },
        displayName = { null },
    )

    @Test
    fun `a reported prompt is delivered`() =
        runBlocking {
            val bus = bus()
            bus.report(prompt("editor"))
            val received = withTimeoutOrNull(1_000) { bus.missingHandlers.first() }
            assertNotNull(received)
            assertEquals("editor", received.missing.pluginId)
        }

    @Test
    fun `a second prompt for the same plugin is not queued`() =
        runBlocking {
            val bus = bus()
            bus.report(prompt("editor", purpose = "Opening a.md"))
            bus.report(prompt("editor", purpose = "Opening b.md"))
            bus.report(prompt("editor", purpose = "Opening c.md"))

            // Twelve files selected in Finder with no editor plugin is one
            // question, not twelve. The first is the one delivered.
            val first = withTimeoutOrNull(1_000) { bus.missingHandlers.first() }
            assertEquals("Opening a.md", first?.missing?.purpose)

            // Nothing else is waiting.
            val second = withTimeoutOrNull(200) { bus.missingHandlers.first() }
            assertNull(second, "a duplicate must not occupy a buffer slot")
        }

    @Test
    fun `a slot is freed once a prompt is taken, so the same plugin can ask again later`() {
        // Block body, not `= runBlocking { ... }`, and that is load-bearing:
        // `assertNotNull` returns the value it checked, so an expression-bodied
        // test ending in one is inferred non-Unit, and JUnit 5 does not run a
        // @Test method that returns a value - it is not reported as skipped
        // either, it simply never appears. Two tests in this file were written
        // that way and silently did not run.
        runBlocking {
            val bus = bus()
            bus.report(prompt("editor"))
            assertNotNull(withTimeoutOrNull(1_000) { bus.missingHandlers.first() })

            // The user closed the dialog without answering (no decline recorded);
            // the next file must be able to ask again.
            bus.report(prompt("editor"))
            assertNotNull(withTimeoutOrNull(1_000) { bus.missingHandlers.first() })
        }
    }

    @Test
    fun `two different plugins both get through`() =
        runBlocking {
            val bus = bus()
            bus.report(prompt("editor"))
            bus.report(prompt("browser"))
            val first = withTimeoutOrNull(1_000) { bus.missingHandlers.first() }
            val second = withTimeoutOrNull(1_000) { bus.missingHandlers.first() }
            assertEquals(setOf("editor", "browser"), setOf(first?.missing?.pluginId, second?.missing?.pluginId))
        }

    @Test
    fun `a declined plugin is never reported again this session`() =
        runBlocking {
            val bus = bus()
            bus.decline("editor")
            assertTrue(bus.wasDeclined("editor"))
            bus.report(prompt("editor"))
            assertNull(
                withTimeoutOrNull(200) { bus.missingHandlers.first() },
                "declining once must answer for the plugin, not for one file",
            )
        }

    @Test
    fun `declining one plugin does not silence another`() {
        // Block body for the same reason as above.
        runBlocking {
            val bus = bus()
            bus.decline("editor")
            assertFalse(bus.wasDeclined("browser"))
            bus.report(prompt("browser"))
            assertNotNull(withTimeoutOrNull(1_000) { bus.missingHandlers.first() })
        }
    }

    @Test
    fun `the remedy travels with the prompt`() =
        runBlocking {
            val bus = bus()
            bus.report(prompt("editor", remedy = MissingHandlerRemedy.ENABLE))
            val received = withTimeoutOrNull(1_000) { bus.missingHandlers.first() }
            // Install and Enable are different buttons doing different things;
            // offering Install for a plugin already on disk cannot fix it.
            assertEquals(MissingHandlerRemedy.ENABLE, received?.missing?.remedy)
        }

    @Test
    fun `reporting never suspends, even past the buffer`() {
        val bus = bus()
        // Capacity is 2 and nothing is collecting. `report` uses trySend, so this
        // must return rather than block the code that was trying to open a file.
        // A DROP_OLDEST channel would accept all of these silently, which is why
        // the overflow is refused and logged instead.
        repeat(10) { index -> bus.report(prompt("plugin-$index")) }
    }
}
