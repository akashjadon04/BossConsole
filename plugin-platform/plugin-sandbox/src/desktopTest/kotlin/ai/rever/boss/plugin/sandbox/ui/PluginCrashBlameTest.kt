package ai.rever.boss.plugin.sandbox.ui

import ai.rever.boss.plugin.sandbox.PluginExecutionBoundary
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Blaming a plugin that has no error boundary on screen.
 *
 * Two questions used to be one, and conflating them cost a session:
 *
 * - [PluginCrashInterceptor.attributeToPlugin] answers "which registered boundary
 *   should handle this", and is filtered to plugins that can render a fallback.
 * - [PluginCrashInterceptor.blameFor] answers "who is responsible", full stop.
 *
 * `TerminalTabPluginAPIImpl.setPendingSidebarCommand` recursed into itself and
 * threw StackOverflowError from a click. All ~1024 frames the JVM kept were
 * `ai.rever.boss.plugin.dynamic.terminaltab.*`, so the culprit was never in
 * doubt - but terminal-tab had no boundary mounted, attribution returned null,
 * and `decideWindowExceptionRoute` fell through to escalation. A plugin with no
 * UI on screen could end the app precisely *because* it had no UI on screen.
 */
class PluginCrashBlameTest {
    private val terminalTab = "ai.rever.boss.plugin.dynamic.terminaltab"

    @BeforeTest
    @AfterTest
    fun reset() {
        KnownPlugins.resetForTest()
        PluginExecutionBoundary.resetForTest()
    }

    /** A stack trace whose frames belong to [pluginId], and to nothing else. */
    private fun recursionIn(pluginId: String): Throwable =
        StackOverflowError().apply {
            stackTrace =
                Array(64) {
                    StackTraceElement(
                        "$pluginId.TerminalTabPluginAPIImpl",
                        "setPendingSidebarCommand",
                        "TerminalTabPluginAPIImpl.kt",
                        218,
                    )
                }
        }

    @Test
    fun `blames a loaded plugin with no boundary mounted`() {
        KnownPlugins.install { setOf(terminalTab) }

        assertEquals(terminalTab, PluginCrashInterceptor.blameFor(recursionIn(terminalTab)))
    }

    /**
     * The narrow question must keep its old answer. A plugin with no boundary has
     * nothing to hand the error to, so `attributeToPlugin` still declines - the
     * route handles it through quarantine instead.
     */
    @Test
    fun `attribution still declines a plugin with no boundary`() {
        KnownPlugins.install { setOf(terminalTab) }

        assertNull(PluginCrashInterceptor.attributeToPlugin(recursionIn(terminalTab)))
    }

    /** The host's own stack is nobody's fault. */
    @Test
    fun `does not blame a plugin for a host stack`() {
        KnownPlugins.install { setOf(terminalTab) }
        val hostFault =
            IllegalStateException("boom").apply {
                stackTrace =
                    arrayOf(
                        StackTraceElement(
                            "ai.rever.boss.components.overlays.ContextMenuKt",
                            "invoke",
                            "ContextMenu.kt",
                            40,
                        ),
                    )
            }

        assertNull(PluginCrashInterceptor.blameFor(hostFault))
    }

    /**
     * A prefix match must respect the package boundary, or `…dynamic.terminal`
     * would answer for every frame of `…dynamic.terminaltab`.
     */
    @Test
    fun `does not blame a plugin whose id is a prefix of the real one`() {
        val sibling = "ai.rever.boss.plugin.dynamic.terminal"
        KnownPlugins.install { setOf(sibling) }

        assertNull(PluginCrashInterceptor.blameFor(recursionIn(terminalTab)))
    }

    /** Nothing installed is the pre-wiring state, and must not throw or guess. */
    @Test
    fun `answers null when the host has installed no plugin list`() {
        assertNull(PluginCrashInterceptor.blameFor(recursionIn(terminalTab)))
    }

    /** A supplier that throws must not be the reason a crash handler fails. */
    @Test
    fun `survives a supplier that throws`() {
        KnownPlugins.install { error("plugin manager is mid-teardown") }

        assertNull(PluginCrashInterceptor.blameFor(recursionIn(terminalTab)))
    }

    /**
     * The execution-boundary tag outranks the stack, and needs no plugin list:
     * it is the exact answer, recorded by the host while calling into the plugin.
     */
    @Test
    fun `prefers the execution-boundary tag over the stack`() {
        val tagged = recursionIn(terminalTab)
        PluginExecutionBoundary.tag(tagged, "some.other.plugin")

        assertEquals("some.other.plugin", PluginCrashInterceptor.blameFor(tagged))
    }
}
