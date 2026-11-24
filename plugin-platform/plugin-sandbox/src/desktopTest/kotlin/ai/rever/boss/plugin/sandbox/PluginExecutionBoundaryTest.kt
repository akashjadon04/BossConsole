package ai.rever.boss.plugin.sandbox

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers [PluginExecutionBoundary], the answer to "whose fault was this?" for a
 * crash the stack can no longer explain.
 *
 * The property that matters is the awkward one: attribution has to survive the
 * stack unwinding completely. The global uncaught-exception handler runs *after*
 * every frame is gone and every `finally` has run, so a mechanism that only knows
 * the answer while the plugin is on the stack answers "the host" at exactly the
 * moment it is asked - and the app gets torn down for a plugin's bug. Half these
 * tests exist to pin that specific ordering.
 */
class PluginExecutionBoundaryTest {
    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
        const val OTHER_PLUGIN = "ai.rever.boss.plugin.dynamic.other"
    }

    @BeforeTest
    fun setUp() = PluginExecutionBoundary.resetForTest()

    @AfterTest
    fun tearDown() = PluginExecutionBoundary.resetForTest()

    @Test
    fun `attribution survives the stack unwinding`() {
        // Exactly the shape of the real path: the throwable is caught far outside
        // the boundary, with nothing of the plugin left on the stack, which is
        // where the uncaught handler looks at it.
        val escaped =
            assertFailsWith<IllegalStateException> {
                PluginExecutionBoundary.runAttributed(PLUGIN) { error("boom") }
            }

        assertEquals(PLUGIN, PluginExecutionBoundary.attributionFor(escaped))
    }

    @Test
    fun `a wrapped exception still names the plugin`() {
        val inner =
            assertFailsWith<IllegalStateException> {
                PluginExecutionBoundary.runAttributed(PLUGIN) { error("boom") }
            }
        // Plugin faults routinely arrive wrapped - InvocationTargetException from
        // a reflective call, CompletionException from a future, Compose's own
        // wrappers - and the tag sits on the cause, not the wrapper.
        val wrapped = RuntimeException("while doing the thing", inner)

        assertEquals(PLUGIN, PluginExecutionBoundary.attributionFor(wrapped))
    }

    @Test
    fun `a self-referential cause chain terminates`() {
        // initCause cannot make a real cycle, but a getCause override can, and a
        // crash handler that hangs is worse than one that misattributes.
        val looping =
            object : RuntimeException("loops") {
                override val cause: Throwable get() = this
            }

        assertNull(PluginExecutionBoundary.attributionFor(looping))
    }

    @Test
    fun `the innermost boundary wins`() {
        // A plugin calling the host calling another plugin. Blame belongs to the
        // one that actually threw, not the one further out that merely relayed it.
        val escaped =
            assertFailsWith<IllegalStateException> {
                PluginExecutionBoundary.runAttributed(OTHER_PLUGIN) {
                    PluginExecutionBoundary.runAttributed(PLUGIN) { error("inner boom") }
                }
            }

        assertEquals(PLUGIN, PluginExecutionBoundary.attributionFor(escaped))
    }

    @Test
    fun `a host throwable is attributed to nobody`() {
        assertNull(PluginExecutionBoundary.attributionFor(IllegalStateException("host bug")))
    }

    @Test
    fun `the current scope is exposed during the call and cleared after it`() {
        var seenInside: String? = null
        PluginExecutionBoundary.runAttributed(PLUGIN) {
            seenInside = PluginExecutionBoundary.currentPluginId()
        }

        assertEquals(PLUGIN, seenInside)
        // Cleared, not merely popped to some previous value: this runs on a pooled
        // thread in production (the EDT, a dispatcher), so a scope left behind
        // would blame this plugin for the next unrelated crash on the same thread.
        assertNull(PluginExecutionBoundary.currentPluginId())
    }

    @Test
    fun `the scope is cleared even when the call throws`() {
        runCatching {
            PluginExecutionBoundary.runAttributed(PLUGIN) { error("boom") }
        }

        assertNull(PluginExecutionBoundary.currentPluginId())
    }

    @Test
    fun `the scope does not leak to other threads`() {
        val inside = CountDownLatch(1)
        val released = CountDownLatch(1)
        var otherThreadSaw: String? = "unset"

        val worker =
            Thread {
                inside.await(5, TimeUnit.SECONDS)
                otherThreadSaw = PluginExecutionBoundary.currentPluginId()
                released.countDown()
            }
        worker.start()
        PluginExecutionBoundary.runAttributed(PLUGIN) {
            inside.countDown()
            released.await(5, TimeUnit.SECONDS)
        }
        worker.join(5_000)

        assertNull(otherThreadSaw, "a plugin scope must not be visible from an unrelated thread")
    }

    @Test
    fun `a nested scope restores the outer plugin, not null`() {
        // The real path: a panel of plugin A rendering a status-bar item contributed
        // by plugin B. Popping B must leave A in scope, or a crash in A's remaining
        // work is attributed to nobody.
        val seen = mutableListOf<String?>()
        PluginExecutionBoundary.runAttributed<Unit>(OTHER_PLUGIN) {
            seen.add(PluginExecutionBoundary.currentPluginId())
            PluginExecutionBoundary.runAttributed<Unit>(PLUGIN) {
                seen.add(PluginExecutionBoundary.currentPluginId())
            }
            seen.add(PluginExecutionBoundary.currentPluginId())
        }

        assertEquals<List<String?>>(listOf(OTHER_PLUGIN, PLUGIN, OTHER_PLUGIN), seen)
        assertNull(PluginExecutionBoundary.currentPluginId(), "and nothing is left behind at the end")
    }

    @Test
    fun `a nested unwind still clears the thread`() {
        // The `if (stack.isEmpty()) executing.remove()` line is what stops the EDT
        // accumulating a per-thread deque forever, and only the outermost pop can
        // reach it.
        runCatching {
            PluginExecutionBoundary.runAttributed(OTHER_PLUGIN) {
                PluginExecutionBoundary.runAttributed(PLUGIN) { error("boom") }
            }
        }

        assertNull(PluginExecutionBoundary.currentPluginId())
    }

    @Test
    fun `a host-owned callback is returned unchanged`() {
        val hostAction = {}

        // Identity, not just behaviour: every context-menu item goes through this,
        // and host items must not pay for an extra frame or an extra allocation.
        assertSame(hostAction, PluginExecutionBoundary.wrapPluginCallback(hostAction))
    }

    @Test
    fun `a plugin-owned callback is wrapped and its throwable tagged`() {
        // A classloader that looks like a PluginClassLoader to the reflective
        // lookup - a public `pluginId` field is the whole contract, which is what
        // keeps this module free of a plugin-loader dependency.
        val loader = FakePluginClassLoader(PLUGIN, javaClass.classLoader)
        val action = loader.loadThrowingAction()

        val wrapped = PluginExecutionBoundary.wrapPluginCallback(action)
        val escaped = assertFailsWith<IllegalStateException> { wrapped() }

        assertTrue(wrapped !== action, "a plugin callback must be wrapped")
        assertEquals(PLUGIN, PluginExecutionBoundary.attributionFor(escaped))
    }

    @Test
    fun `an installed resolver overrules what a classloader claims about itself`() {
        // Attribution now selects which plugin gets disabled and written out of
        // installed.json, so "whatever this loader says its id is" is too weak. A
        // plugin defining classes through a nested loader of its own - a scripting
        // engine, an embedded framework - controls that answer and could name a
        // rival. With the host's resolver installed, only what the host recognises
        // counts, and this loader is not it.
        val spoofing = FakePluginClassLoader(OTHER_PLUGIN, javaClass.classLoader)
        val action = spoofing.loadThrowingAction()
        // Stands in for `(loader as? PluginClassLoader)?.pluginId`: a type check
        // against something only the host constructs.
        PluginExecutionBoundary.resetForTest { loader ->
            if (loader is TrustedLoader) loader.trustedPluginId else null
        }

        assertNull(
            PluginExecutionBoundary.pluginIdOfOwner(action),
            "a loader the host does not recognise attributes to nobody, whatever it claims",
        )
        assertSame(action, PluginExecutionBoundary.wrapPluginCallback(action), "and so it is not wrapped")
    }

    @Test
    fun `an installed resolver is what identifies a recognised loader`() {
        val trusted = TrustedLoader(PLUGIN, javaClass.classLoader)
        val action = trusted.loadThrowingAction()
        PluginExecutionBoundary.resetForTest { loader ->
            if (loader is TrustedLoader) loader.trustedPluginId else null
        }

        assertEquals(PLUGIN, PluginExecutionBoundary.pluginIdOfOwner(action))
    }

    @Test
    fun `the first resolver install wins`() {
        // The setter was public once, and this object is reachable from plugin code
        // (plugin.sandbox is not a shared package, so a child-first miss delegates
        // to the parent). A second install must not be able to reroute every
        // attribution in the process.
        val trusted = TrustedLoader(PLUGIN, javaClass.classLoader)
        val action = trusted.loadThrowingAction()
        PluginExecutionBoundary.installPluginIdResolver { loader ->
            if (loader is TrustedLoader) loader.trustedPluginId else null
        }

        PluginExecutionBoundary.installPluginIdResolver { OTHER_PLUGIN }

        assertEquals(PLUGIN, PluginExecutionBoundary.pluginIdOfOwner(action), "the first install stands")
    }

    @Test
    fun `the first tag wins`() {
        val error = IllegalStateException("boom")
        PluginExecutionBoundary.tag(error, PLUGIN)
        PluginExecutionBoundary.tag(error, OTHER_PLUGIN)

        // Re-tagging on the way out through outer layers must not overwrite the
        // attribution taken closest to the fault.
        assertEquals(PLUGIN, PluginExecutionBoundary.attributionFor(error))
    }

    /**
     * Stands in for `PluginClassLoader` without depending on it.
     *
     * `wrapPluginCallback` resolves the id off the *defining* classloader of the
     * lambda, so the fixture has to genuinely define the class the lambda belongs
     * to - hence a real classloader loading the action class from this test's own
     * bytes rather than delegating to the parent.
     *
     * `pluginId` is a plain Kotlin `val`, **not** `@JvmField`, because that is what
     * `PluginClassLoader` declares: a constructor property, i.e. a private backing
     * field plus a public `getPluginId()`. The `@JvmField` this fixture used at
     * first was the only form a `getField` lookup can see, so it made the test
     * pass against a production type the code could not actually read. Do not add
     * it back. `CrashHandlerAttributionTest` pins the same thing against the real
     * loader, which is the assertion that cannot be faked.
     */

    /**
     * Defines [ThrowingAction] in itself, so the action's class - and therefore its
     * classloader - is this loader rather than the test's.
     *
     * `defineClass` is protected, so a subclass can call it directly; going through
     * reflection instead fails under the module system ("java.base does not opens
     * java.lang"), which is worth stating because it looks like the obvious route.
     */
    private abstract class DefiningLoader(
        parent: ClassLoader,
    ) : ClassLoader(parent) {
        fun loadThrowingAction(): () -> Unit {
            val name = ThrowingAction::class.java.name
            val bytes =
                checkNotNull(parent.getResourceAsStream(name.replace('.', '/') + ".class")) {
                    "fixture class not on the test classpath"
                }.use { it.readBytes() }

            @Suppress("UNCHECKED_CAST")
            val defined = defineClass(name, bytes, 0, bytes.size) as Class<out () -> Unit>
            return defined.getDeclaredConstructor().newInstance()
        }
    }

    /**
     * Stands in for `PluginClassLoader` when NO resolver is installed.
     *
     * `pluginId` is a plain Kotlin `val`, **not** `@JvmField`, because that is what
     * `PluginClassLoader` declares: a constructor property, i.e. a private backing
     * field plus a public `getPluginId()`. The `@JvmField` this fixture used at
     * first was the only form a `getField` lookup can see, so it made the test pass
     * against a production shape the code could not actually read. Do not add it
     * back. `CrashHandlerAttributionTest` pins the same thing against the real
     * loader, which is the assertion that cannot be faked.
     */
    private class FakePluginClassLoader(
        val pluginId: String,
        parent: ClassLoader,
    ) : DefiningLoader(parent)

    /** A loader the fake resolver recognises, standing in for `PluginClassLoader`. */
    private class TrustedLoader(
        val trustedPluginId: String,
        parent: ClassLoader,
    ) : DefiningLoader(parent)

    /** A plugin-authored callback: public, no-arg constructor, throws when invoked. */
    class ThrowingAction : () -> Unit {
        override fun invoke(): Unit = error("plugin action boom")
    }
}
