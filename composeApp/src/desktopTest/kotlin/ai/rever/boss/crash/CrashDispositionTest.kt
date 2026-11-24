package ai.rever.boss.crash

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Covers [classifyCrash], which decides whether a crash costs the user a plugin
 * or the whole session.
 *
 * Every case here is one where getting it wrong is expensive in one direction or
 * the other: classify a host fault as recoverable and the app limps on broken
 * with a plugin blamed for someone else's bug; classify a plugin fault as fatal
 * and every window, tab and terminal session dies over a bad menu handler.
 */
class CrashDispositionTest {
    private companion object {
        const val PLUGIN = "ai.rever.boss.plugin.dynamic.probe"
    }

    private val pluginFault = IllegalStateException("plugin boom")

    @Test
    fun `an attributed crash with recovery available is recoverable`() {
        val disposition = classifyCrash(pluginFault, PLUGIN, recoveryAvailable = true)

        assertEquals(CrashDisposition.RecoverablePlugin(PLUGIN), disposition)
    }

    @Test
    fun `an unattributed crash is fatal`() {
        assertIs<CrashDisposition.FatalHost>(
            classifyCrash(IllegalStateException("host bug"), pluginId = null, recoveryAvailable = true),
        )
    }

    @Test
    fun `a blank plugin id is fatal`() {
        // Attribution returning "" is a bug, not a plugin - and recovering
        // "nothing" would dismiss the dialog having done nothing at all.
        assertIs<CrashDisposition.FatalHost>(
            classifyCrash(pluginFault, pluginId = "  ", recoveryAvailable = true),
        )
    }

    @Test
    fun `heap exhaustion is fatal even when a plugin is on the hook`() {
        // Disabling a plugin gives no memory back, and every step of recovery -
        // closing tabs, unloading, painting a toast - allocates. The render path
        // carves OOM out for the same reason; these two must agree or the carve-out
        // is worthless.
        assertIs<CrashDisposition.FatalHost>(
            classifyCrash(OutOfMemoryError("heap"), PLUGIN, recoveryAvailable = true),
        )
    }

    @Test
    fun `a blown stack is fatal even when a plugin is on the hook`() {
        assertIs<CrashDisposition.FatalHost>(
            classifyCrash(StackOverflowError(), PLUGIN, recoveryAvailable = true),
        )
    }

    @Test
    fun `a wrapped heap exhaustion is still fatal`() {
        // How these actually arrive: attribution walks twelve causes precisely
        // because wrapping is routine, so a flat check on the top-level throwable
        // let InvocationTargetException(cause = OutOfMemoryError) classify as
        // recoverable - and recovery then allocates a toast, launches a coroutine
        // and tears down tabs across every window on an exhausted heap.
        val wrapped =
            java.lang.reflect.InvocationTargetException(OutOfMemoryError("heap"))

        assertIs<CrashDisposition.FatalHost>(classifyCrash(wrapped, PLUGIN, recoveryAvailable = true))
    }

    @Test
    fun `a deeply wrapped blown stack is still fatal`() {
        val wrapped = RuntimeException("outer", IllegalStateException("mid", StackOverflowError()))

        assertIs<CrashDisposition.FatalHost>(classifyCrash(wrapped, PLUGIN, recoveryAvailable = true))
    }

    @Test
    fun `a self-referential cause chain does not hang classification`() {
        // A crash handler that hangs is worse than one that misattributes.
        val looping =
            object : RuntimeException("loops") {
                override val cause: Throwable get() = this
            }

        assertEquals(CrashDisposition.RecoverablePlugin(PLUGIN), classifyCrash(looping, PLUGIN, true))
    }

    @Test
    fun `without a recovery seam an attributed crash is still fatal`() {
        // The honest answer for a run with no plugin layer (headless, or a crash
        // before it is wired). Claiming recoverable here would dismiss the dialog
        // and leave the crashing plugin running.
        assertIs<CrashDisposition.FatalHost>(
            classifyCrash(pluginFault, PLUGIN, recoveryAvailable = false),
        )
    }
}
