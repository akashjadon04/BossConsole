package ai.rever.boss.crash.pluginprobe

/**
 * Test fixture for [ai.rever.boss.crash.CrashHandlerAttributionTest].
 *
 * These classes are compiled onto the desktopTest classpath, but the test
 * repackages their .class bytes into a standalone jar and loads them through a
 * real PluginClassLoader — producing throwables whose stack frames (and, for
 * [ProbeException], whose class itself) are DEFINED by a plugin classloader,
 * exactly like a crash inside a dynamically loaded plugin.
 */
class PluginProbe {
    fun boom(): Unit = throw IllegalStateException("probe boom")

    fun boomCustom(): Unit = throw ProbeException()
}

class ProbeException : RuntimeException("custom probe exception")

/**
 * A lambda created inside plugin-loaded code.
 *
 * Kotlin 2.x compiles this to an `invokedynamic` call site backed by a hidden
 * class, whose `getClassLoader()` is the loader of its host class - the plugin's,
 * here. `PluginExecutionBoundary.wrapPluginCallback` depends on exactly that, and
 * a compiler or `-Xlambdas` change flipping it would make every plugin callback
 * look host-owned, silently un-attributed, with no visible failure until a
 * session dies over a plugin's bug.
 */
fun probeAction(): () -> Unit = { throw IllegalStateException("probe lambda boom") }

/**
 * A plugin-owned callback that reports back through a host-owned sink.
 *
 * The lambda's class is defined by the plugin loader, so `pluginIdOfOwner`
 * resolves it; the sink is host-owned and reads the *current* attribution scope
 * from inside the call. A Runnable, because nothing is passed through it - what the
 * test learns comes from what it observes while running. That combination is what makes "was this invoked inside
 * its plugin's scope?" observable from a test without plugin code needing to
 * reach host internals.
 */
fun probeReporter(sink: Runnable): () -> Unit = { sink.run() }
