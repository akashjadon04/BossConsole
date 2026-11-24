package ai.rever.boss.plugin.browser

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the force-kill half of [FluckEngine.recycleWedgedEngine] against the two ways it
 * silently did nothing in production.
 *
 * A wedged engine's Chromium tree outlived every recycle: its `close()` failed fast on dead IPC
 * (so a timeout-only test read it as success), and the generic sweeper skips anything parented to
 * this process, which every engine we spawn is. The tree kept the profile lock - sending the
 * replacement engine to a throwaway temp profile, which signs the user out of every site - and
 * kept its audio helper playing the video from a tab that had already been closed.
 *
 * Real processes rather than mocks, because both failures were about what the OS does, not about
 * what the code believes: a helper that has been reparented cannot be found from its old parent,
 * and SIGTERM is a request a stopped or wedged process is free to ignore.
 */
class FluckEngineProcessKillTest {
    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    @Test
    fun `kills the whole tree, including a child that outlives its parent`() =
        runBlocking {
            // Reparenting to pid 1 is what makes ordering matter, and it is a POSIX rule: on
            // Windows an orphan keeps its recorded parent id and `destroy()` is TerminateProcess
            // either way. There is no /bin/sh to build the tree with there either.
            assumeTrue(!isWindows, "Orphan reparenting is POSIX behaviour")

            // The shell backgrounds a sleep and then sleeps itself: two processes, and the
            // grandchild is exactly the shape of Chromium's audio helper - it is reparented to
            // pid 1 the moment its parent dies, after which nothing can reach it from here.
            val parent = ProcessBuilder("/bin/sh", "-c", "sleep 120 & sleep 120").start()
            val parentHandle = parent.toHandle()
            val children = waitForDescendants(parentHandle, expected = 2)

            FluckEngine.killEngineProcesses(listOf(parentHandle))

            assertFalse(parentHandle.isAlive, "engine process survived the kill")
            children.forEach { child ->
                assertFalse(child.isAlive, "descendant ${child.pid()} survived the kill")
            }
        }

    @Test
    fun `escalates to SIGKILL for a process that ignores SIGTERM`() =
        runBlocking {
            // Nothing to escalate from on Windows: destroy() and destroyForcibly() are both
            // TerminateProcess, which cannot be trapped or ignored.
            assumeTrue(!isWindows, "SIGTERM is advisory only on POSIX")

            // A wedged Chromium is precisely this: alive, signalled, and unmoved. destroy() is a
            // request; only destroyForcibly() frees the --user-data-dir lock.
            val stubborn = ProcessBuilder("/bin/sh", "-c", "trap '' TERM; sleep 120").start()
            val handle = stubborn.toHandle()
            assertTrue(handle.isAlive, "test process did not start")

            FluckEngine.killEngineProcesses(listOf(handle))

            assertFalse(handle.isAlive, "process ignoring SIGTERM was never force-killed")
        }

    /**
     * The shell forks its children asynchronously, so the tree is not complete the instant
     * `start()` returns. Polls rather than sleeping a fixed amount, since a short sleep that is
     * occasionally too short would make this test assert the kill of an empty list and pass.
     */
    private fun waitForDescendants(
        parent: ProcessHandle,
        expected: Int,
    ): List<ProcessHandle> {
        val deadline = System.currentTimeMillis() + 5_000
        var found = parent.descendants().toList()
        while (found.size < expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
            found = parent.descendants().toList()
        }
        assertTrue(
            found.size >= expected,
            "expected at least $expected descendants to kill, found ${found.size} - the test would prove nothing",
        )
        return found
    }
}
