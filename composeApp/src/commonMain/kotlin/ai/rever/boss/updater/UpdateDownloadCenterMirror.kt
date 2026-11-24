package ai.rever.boss.updater

import ai.rever.boss.downloads.DownloadCenter
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Publishes the application's own update into [DownloadCenter], so it shares the
 * bottom-bar progress item and the dialog with every plugin transfer.
 *
 * A mirror rather than a second source: [UpdateManager.updateState] stays the one
 * place the update's progress lives (the top banner reads the same flow), and this
 * translates it. Two states writing the same row from different places is how a
 * banner and a bar come to disagree about whether an install has started.
 *
 * The row carries the actions the state allows, and only those:
 *
 * | State | Row |
 * |---|---|
 * | `Downloading` | progress, Cancel |
 * | `ReadyToInstall` | Install, Cancel (discards the staged file) |
 * | `Installing` | no actions - file moves and an elevated helper, past abandoning |
 * | anything else | no row |
 */
object UpdateDownloadCenterMirror {
    /**
     * Start mirroring. Idempotent: a second call is ignored, so every window may
     * ask for it without N collectors racing to write one row.
     *
     * Runs on the manager's own scope, the same one the download runs on - a
     * window's scope dies with its composition and would stop mirroring a
     * download that is still going.
     */
    fun start(coordinator: UpdateCoordinator) {
        val manager = coordinator.manager
        // compareAndSet rather than a check on a @Volatile: two windows starting at
        // once would both pass check-then-act and launch a collector each, which is
        // not the "every window may ask" this claims to be. The loser cancels the
        // collector it launched. The expected value is the job we read rather than
        // null, so a dead one (only app shutdown kills the scope) can be replaced
        // instead of blocking every later start forever.
        val existing = job.get()
        if (existing?.isActive == true) return
        val started =
            manager.launchInBackground {
                manager.updateState.collect { state -> publish(state, manager) }
            }
        if (!job.compareAndSet(existing, started)) started.cancel()
    }

    private val job = AtomicReference<Job?>(null)

    /** Internal for the mapping test: the table above is the whole behaviour. */

    /**
     * The actions, allocated once per manager rather than per emission.
     *
     * Identity matters: `DownloadCenter.begin` decides "another operation joined" by
     * comparing cancel actions by reference, and a fresh lambda every progress tick
     * made that fire on every tick - withdrawing Cancel, having `setActions` put it
     * straight back, and rebuilding the list three times where one would do. A guard
     * that fires constantly for the one caller it does not mean is no guard at all.
     */
    private val actions = ConcurrentHashMap<UpdateManager, UpdateActions>()

    private class UpdateActions(
        manager: UpdateManager,
    ) {
        val cancelDownload: () -> Unit = { manager.cancelDownload() }
        val discardDownload: () -> Unit = { manager.launchInBackground { manager.discardDownload() } }
    }

    private fun actionsFor(manager: UpdateManager) = actions.computeIfAbsent(manager) { UpdateActions(it) }

    internal fun publish(
        state: UpdateState,
        manager: UpdateManager,
    ) {
        val id = DownloadCenter.APP_UPDATE_ID
        val acts = actionsFor(manager)
        when (state) {
            is UpdateState.Downloading -> {
                DownloadCenter.begin(
                    id = id,
                    title = title(manager),
                    kind = TransferKind.APP_UPDATE,
                    detail = "Application update",
                    onCancel = acts.cancelDownload,
                )
                // Asserted, not left to begin: a row already exists when a second
                // version is picked from the list while one sits ReadyToInstall, and
                // begin leaves an existing row alone. Without this the row keeps the
                // ready-state actions - a Cancel that calls discardDownload() and
                // early-returns because the state moved on, so the button renders
                // enabled and does nothing, and an Install still holding the previous
                // staged path.
                DownloadCenter.setActions(
                    id = id,
                    onCancel = acts.cancelDownload,
                    onInstall = null,
                )
                DownloadCenter.progress(id, state.progress)
            }

            is UpdateState.ReadyToInstall -> {
                DownloadCenter.begin(id, title(manager), TransferKind.APP_UPDATE, "Application update")
                // Set after begin, not through it: a download that just finished
                // already has a row, and begin leaves an existing one alone.
                val staged = state.downloadPath
                DownloadCenter.setActions(
                    id = id,
                    onCancel = acts.discardDownload,
                    onInstall = { manager.launchInBackground { manager.installUpdate(staged) } },
                )
                DownloadCenter.phase(id, TransferPhase.READY_TO_INSTALL)
            }

            is UpdateState.Installing -> {
                DownloadCenter.begin(id, title(manager), TransferKind.APP_UPDATE, "Application update")
                // Both dropped: an Install left in place would offer a second elevated
                // install of the artifact this one is already installing.
                DownloadCenter.setActions(id, onCancel = null, onInstall = null)
                DownloadCenter.phase(id, TransferPhase.INSTALLING)
            }

            // Idle, UpToDate, CheckingForUpdates, UpdateAvailable, RestartRequired, Error.
            // None of them is work in flight: an available update is an offer the banner
            // and the dialog make, and a failure is reported there too - a row that
            // stayed would be a progress bar for something that is not happening.
            else -> {
                DownloadCenter.end(id)
            }
        }
    }

    /** "BOSS v9.4.33" when a version is known, and just "BOSS" before any check named one. */
    private fun title(manager: UpdateManager): String {
        val version = manager.updateInfo.value?.latestVersion ?: return "BOSS"
        return "BOSS v$version"
    }
}
