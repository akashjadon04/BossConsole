package ai.rever.boss.plugin.sandbox.ui

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the signal the unload path waits on before closing a plugin's classloader.
 *
 * The bug it exists for: closing a plugin's tabs mutates the tab model on the EDT and returns,
 * while Compose disposes the subtree on a LATER render frame - and that frame is what runs the
 * plugin's own `onDispose` lambdas. Unloading in between leaves those lambdas unable to resolve
 * their own classes, which surfaces as a `NoClassDefFoundError` blaming a reference that outlived
 * the unload. Waiting on this registry is what closes that window.
 *
 * The timeout half is as load-bearing as the waiting half: a window that never draws would
 * otherwise hang an unload forever.
 */
class PluginUiMountRegistryTest {
    @BeforeTest
    fun setUp() = PluginUiMountRegistry.reset()

    @AfterTest
    fun tearDown() = PluginUiMountRegistry.reset()

    @Test
    fun `a mounted plugin is mounted until it is disposed`() {
        PluginUiMountRegistry.onMounted("terminal")

        assertTrue(PluginUiMountRegistry.isMounted("terminal"))
        assertTrue(PluginUiMountRegistry.isMounted(), "any plugin at all")

        PluginUiMountRegistry.onDisposed("terminal")

        assertFalse(PluginUiMountRegistry.isMounted("terminal"))
        assertFalse(PluginUiMountRegistry.isMounted())
    }

    @Test
    fun `surfaces are counted, not flagged`() {
        // One plugin can have a tab in each of two windows, or a tab and a sidebar panel. They
        // dispose independently, and the first one to go must not report the plugin as gone -
        // which is exactly when the loader would close under the second.
        PluginUiMountRegistry.onMounted("terminal")
        PluginUiMountRegistry.onMounted("terminal")
        PluginUiMountRegistry.onDisposed("terminal")

        assertTrue(PluginUiMountRegistry.isMounted("terminal"), "one surface is still up")

        PluginUiMountRegistry.onDisposed("terminal")
        assertFalse(PluginUiMountRegistry.isMounted("terminal"))
    }

    @Test
    fun `awaiting does not return until the last surface disposes`() =
        runBlocking {
            // Asserted as "had the dispose happened yet", not as "did it return true". The first
            // version of this test only checked the return value, which is true whether the wait
            // waited or returned instantly - so it passed against the very behaviour it was
            // written to catch.
            PluginUiMountRegistry.onMounted("terminal")
            var disposeHappened = false

            val waiter =
                async {
                    val reached = PluginUiMountRegistry.awaitDisposed(setOf("terminal"), timeoutMillis = 5_000)
                    reached to disposeHappened
                }
            delay(20)
            disposeHappened = true
            PluginUiMountRegistry.onDisposed("terminal")

            val (reached, sawDispose) = waiter.await()
            assertTrue(reached, "the wait must end on the dispose, not on the timeout")
            assertTrue(sawDispose, "the wait returned before the surface had disposed")
        }

    @Test
    fun `awaiting nothing returns immediately`() =
        runBlocking {
            // The common case: an unload with no plugin UI on screen must not pay the timeout.
            assertTrue(PluginUiMountRegistry.awaitDisposed(setOf("terminal"), timeoutMillis = 5_000))
            assertTrue(PluginUiMountRegistry.awaitDisposed(setOf("terminal", "editor"), timeoutMillis = 5_000))
        }

    @Test
    fun `a surface that never disposes times out rather than hanging`() =
        runBlocking {
            // A window that is minimised or fully occluded may never draw another frame. Blocking
            // an unload on it would be worse than the fault the crash boundary already contains.
            PluginUiMountRegistry.onMounted("terminal")

            assertFalse(PluginUiMountRegistry.awaitDisposed(setOf("terminal"), timeoutMillis = 50))
        }

    @Test
    fun `awaiting one plugin ignores another still on screen`() =
        runBlocking {
            // Unloading one plugin must not wait on an unrelated plugin's UI, which is not going
            // anywhere and whose loader is not closing. This is what keeps a sidebar panel that
            // deliberately survives an API swap from burning the whole timeout on every swap.
            PluginUiMountRegistry.onMounted("editor")

            assertTrue(PluginUiMountRegistry.awaitDisposed(setOf("terminal"), timeoutMillis = 5_000))
            assertFalse(
                PluginUiMountRegistry.awaitDisposed(setOf("terminal", "editor"), timeoutMillis = 50),
                "a set that names the mounted plugin must wait for it",
            )
        }

    @Test
    fun `awaiting an empty set returns immediately`() =
        runBlocking {
            // An unload with no sandboxed surfaces at all. It must not pay the timeout, and it
            // must not wait on unrelated plugins that happen to be mounted.
            PluginUiMountRegistry.onMounted("editor")

            assertTrue(PluginUiMountRegistry.awaitDisposed(emptySet(), timeoutMillis = 50))
        }

    @Test
    fun `stillMounted reports only the plugins that were awaited`() =
        runBlocking {
            // The timeout warning is built from this. Listing every mounted plugin named ones with
            // nothing to do with the unload, which is what made the line unactionable.
            PluginUiMountRegistry.onMounted("terminal")
            PluginUiMountRegistry.onMounted("editor")

            assertEquals(mapOf("terminal" to 1), PluginUiMountRegistry.stillMounted(setOf("terminal")))
        }

    @Test
    fun `disposing more than was mounted does not go negative`() {
        // Compose can dispose a boundary whose mount this registry never saw - a composition that
        // existed before a reset, for instance. Going negative would then hide a later real mount.
        PluginUiMountRegistry.onDisposed("terminal")
        PluginUiMountRegistry.onMounted("terminal")

        assertTrue(PluginUiMountRegistry.isMounted("terminal"))
        assertEquals(1, PluginUiMountRegistry.mounted.value["terminal"])
    }
}
