package ai.rever.boss.updater

import ai.rever.boss.utils.Version
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Installing and discarding are the same staged artifact seen two ways, so exactly one of them
 * may ever win.
 *
 * Both used to read the state, then act, with a real coroutine dispatch in between - every caller
 * goes through `launchInBackground`, and `installUpdate` only moved the state to `Installing` once
 * it had been dispatched. So Install then Cancel, two buttons 8dp apart on the update banner, let
 * both pass their guards: the discard deleted the artifact while the install was opening it, and
 * the user got neither outcome they asked for.
 *
 * Tested on the claim rather than through [UpdateManager] on purpose. `UpdateManager` builds its
 * own `UpdateService`, so a test that drove the real methods would either need a seam that does not
 * exist or would reach a real installer - and the race is entirely in the compare-and-set, which
 * this exercises directly and deterministically.
 */
class StagedUpdateClaimTest {
    private fun staged(path: String = "/tmp/BOSS.dmg") = UpdateState.ReadyToInstall(downloadPath = path)

    private fun updateInfo() =
        UpdateInfo(
            available = true,
            currentVersion = Version(major = 9, minor = 5, patch = 2),
            latestVersion = Version(major = 9, minor = 5, patch = 3),
            releaseNotes = "",
            downloadUrl = "https://example.invalid/BOSS.dmg",
        )

    @Test
    fun `the winner gets the staged file and the loser is told to leave it alone`() {
        val state = MutableStateFlow<UpdateState>(staged())

        val install = state.claimStagedUpdate { UpdateState.Installing }
        val discard = state.claimStagedUpdate { UpdateState.Idle }

        // The claimed value carries the path, so the winner does not have to re-read a state
        // the loser may already have moved.
        assertEquals("/tmp/BOSS.dmg", assertNotNull(install).downloadPath)
        assertNull(discard, "the second caller must be refused - this is the delete that used to happen anyway")
        assertEquals(UpdateState.Installing, state.value)
    }

    @Test
    fun `order does not matter - discard first refuses the install`() {
        val state = MutableStateFlow<UpdateState>(staged())

        val discard = state.claimStagedUpdate { UpdateState.UpdateAvailable(updateInfo()) }
        val install = state.claimStagedUpdate { UpdateState.Installing }

        assertNotNull(discard)
        // The reverse of the case above, and the one the earlier fix attempt still got wrong: an
        // install that fell back to setting Installing whenever the state was NOT ReadyToInstall
        // would sail straight past a completed discard and install a deleted file.
        assertNull(install, "installing after a discard has won means installing a file that is gone")
    }

    @Test
    fun `nothing staged means nothing to claim`() {
        listOf(UpdateState.Idle, UpdateState.Installing, UpdateState.RestartRequired, UpdateState.Downloading(0.5f))
            .forEach { start ->
                val state = MutableStateFlow<UpdateState>(start)
                assertNull(state.claimStagedUpdate { UpdateState.Installing }, "$start is not a staged update")
                assertEquals(start, state.value, "a refused claim must not move the state")
            }
    }

    @Test
    fun `a second press installs once, not twice`() {
        val state = MutableStateFlow<UpdateState>(staged())

        // The banner's Install Now is a plain button - unlike the download center's dialog, which
        // clears its action when it fires. Two quick presses used to mean two elevated installers
        // for one artifact.
        val first = state.claimStagedUpdate { UpdateState.Installing }
        val second = state.claimStagedUpdate { UpdateState.Installing }

        assertNotNull(first)
        assertNull(second)
    }

    @Test
    fun `under real contention exactly one of many claimants wins`() =
        runBlocking {
            repeat(200) {
                val state = MutableStateFlow<UpdateState>(staged())
                val wins = AtomicInteger()

                // Whether the destination differs or not is irrelevant to the invariant: one
                // claimant proceeds. `to` is a lambda so it is only evaluated by the winner.
                val claimants =
                    (1..8).map { i ->
                        async {
                            val target = if (i % 2 == 0) UpdateState.Installing else UpdateState.Idle
                            if (state.claimStagedUpdate { target } != null) wins.incrementAndGet()
                        }
                    }
                claimants.awaitAll()

                assertEquals(1, wins.get(), "exactly one claimant may own the staged artifact")
            }
        }
}
