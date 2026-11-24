package ai.rever.boss.crash

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Covers how an exception escaping the Compose render loop is routed.
 *
 * The case that matters most is the one that used to be only an argument in a
 * comment: [PluginRenderBoundary][ai.rever.boss.plugin.sandbox.ui.PluginRenderBoundary]
 * deliberately rethrows [OutOfMemoryError] rather than blaming a plugin for a
 * dead JVM — and that carve-out was worthless, because the rethrown error landed
 * here and got contained anyway, which logs, toasts and repaints every window.
 * Under heap exhaustion that is more allocation, three times over, before the
 * circuit breaker gives up.
 */
class WindowExceptionRouteTest {
    private fun policy() = RenderCrashPolicy(now = { 0L })

    @Test
    fun `an attributed plugin crash is left to the interceptor`() {
        val route =
            decideWindowExceptionRoute(
                IllegalStateException("boom"),
                attributedPluginId = "ai.rever.boss.plugin.dynamic.bookmarks",
                policy = policy(),
            )

        assertEquals(WindowExceptionRoute.PluginHandled, route)
    }

    @Test
    fun `an ordinary unattributed fault is contained`() {
        val route = decideWindowExceptionRoute(IllegalStateException("boom"), null, policy())

        assertEquals(WindowExceptionRoute.Contain, route)
    }

    @Test
    fun `an OutOfMemoryError escalates instead of being contained`() {
        val route = decideWindowExceptionRoute(OutOfMemoryError("Java heap space"), null, policy())

        assertEquals(
            WindowExceptionRoute.Escalate,
            route,
            "containing an OOM means repainting every window under heap exhaustion",
        )
    }

    @Test
    fun `a StackOverflowError escalates`() {
        val route = decideWindowExceptionRoute(StackOverflowError(), null, policy())

        assertEquals(WindowExceptionRoute.Escalate, route)
    }

    @Test
    fun `an uncontainable error does not consume a containment slot`() {
        // Order matters: if the uncontainable check sat below the policy call, an
        // OOM would burn budget meant for faults that can actually be contained.
        val policy = policy()
        repeat(5) { decideWindowExceptionRoute(OutOfMemoryError(), null, policy) }

        assertEquals(0, policy.recentFailureCount(), "escalated faults must not be recorded as containable ones")
        assertEquals(
            WindowExceptionRoute.Contain,
            decideWindowExceptionRoute(IllegalStateException("boom"), null, policy),
            "a real containable fault should still have its full budget",
        )
    }

    @Test
    fun `a sustained burst eventually escalates`() {
        val policy = policy()
        repeat(RenderCrashPolicy.DEFAULT_MAX_FAILURES) {
            assertEquals(
                WindowExceptionRoute.Contain,
                decideWindowExceptionRoute(IllegalStateException("boom"), null, policy),
            )
        }

        assertEquals(
            WindowExceptionRoute.Escalate,
            decideWindowExceptionRoute(IllegalStateException("boom"), null, policy),
        )
    }

    @Test
    fun `a wrapped OutOfMemoryError escalates too`() {
        // The two uncontainable carve-outs - this one and classifyCrash's - are
        // worthless unless they agree, and for one round they did not: the
        // cause-chain fix landed on classifyCrash and left this one flat, so the
        // identical wrapped error was escalated there and contained here. Both now
        // go through causeChain.
        val wrapped = java.lang.reflect.InvocationTargetException(OutOfMemoryError("heap"))

        assertEquals(
            WindowExceptionRoute.Escalate,
            decideWindowExceptionRoute(wrapped, attributedPluginId = null, policy = policy()),
        )
    }

    @Test
    fun `an attributed crash is never escalated, even when fatal`() {
        // The interceptor owns anything it can attribute; this branch should not
        // second-guess it.
        val route = decideWindowExceptionRoute(OutOfMemoryError(), "some.plugin", policy())

        assertEquals(WindowExceptionRoute.PluginHandled, route)
    }

    // ---------------------------------------------------------------- blame

    /**
     * The case this branch exists for.
     *
     * `TerminalTabPluginAPIImpl.setPendingSidebarCommand` recursed into itself and
     * threw StackOverflowError from a click. Every one of the ~1024 frames the JVM
     * kept named the plugin, but terminal-tab had no boundary mounted, so
     * attribution returned null and BOSS exited. The stack has unwound by the time
     * we route; the process is fine, and there is a plugin to remove.
     */
    @Test
    fun `a StackOverflowError blamed on a plugin is quarantined instead of ending the app`() {
        val route =
            decideWindowExceptionRoute(
                StackOverflowError(),
                attributedPluginId = null,
                policy = policy(),
                blamedPluginId = "ai.rever.boss.plugin.dynamic.terminaltab",
            )

        assertEquals(WindowExceptionRoute.QuarantinePlugin, route)
    }

    /**
     * An OOM is fatal to the *process*, not merely uncontainable by a repaint.
     * Blame changes nothing: every recovery path allocates, and there is no heap
     * left to allocate from. This ordering is why hasFatalCause sits above the
     * blame branch rather than below it.
     */
    @Test
    fun `an OutOfMemoryError escalates even when a plugin is to blame`() {
        val route =
            decideWindowExceptionRoute(
                OutOfMemoryError("Java heap space"),
                attributedPluginId = null,
                policy = policy(),
                blamedPluginId = "some.plugin",
            )

        assertEquals(WindowExceptionRoute.Escalate, route)
    }

    @Test
    fun `a wrapped OutOfMemoryError escalates even when a plugin is to blame`() {
        val wrapped = java.lang.reflect.InvocationTargetException(OutOfMemoryError("heap"))

        assertEquals(
            WindowExceptionRoute.Escalate,
            decideWindowExceptionRoute(wrapped, null, policy(), blamedPluginId = "some.plugin"),
        )
    }

    /** A live boundary still wins: it can show a fallback, quarantining cannot. */
    @Test
    fun `an attributed crash prefers the boundary over quarantine`() {
        val route =
            decideWindowExceptionRoute(
                StackOverflowError(),
                attributedPluginId = "with.boundary",
                policy = policy(),
                blamedPluginId = "with.boundary",
            )

        assertEquals(WindowExceptionRoute.PluginHandled, route)
    }

    /** Nobody to blame leaves the previous behaviour exactly as it was. */
    @Test
    fun `an unblamed StackOverflowError still escalates`() {
        assertEquals(
            WindowExceptionRoute.Escalate,
            decideWindowExceptionRoute(StackOverflowError(), null, policy(), blamedPluginId = null),
        )
    }

    /**
     * An ordinary fault with a name on it is quarantined rather than contained:
     * containment is the narrowing loop for faults nobody can place, and running
     * it when we already know the answer would rebuild every panel to rediscover
     * it.
     */
    @Test
    fun `a blamed ordinary fault is quarantined rather than contained`() {
        assertEquals(
            WindowExceptionRoute.QuarantinePlugin,
            decideWindowExceptionRoute(IllegalStateException("boom"), null, policy(), "some.plugin"),
        )
    }

    /** Quarantine must not consume containment budget - it is not a containment. */
    @Test
    fun `quarantining does not spend a containment slot`() {
        val policy = policy()
        repeat(5) { decideWindowExceptionRoute(StackOverflowError(), null, policy, "some.plugin") }

        assertEquals(0, policy.recentFailureCount())
        assertEquals(
            WindowExceptionRoute.Contain,
            decideWindowExceptionRoute(IllegalStateException("boom"), null, policy),
            "a real containable fault should still have its full budget",
        )
    }
}
