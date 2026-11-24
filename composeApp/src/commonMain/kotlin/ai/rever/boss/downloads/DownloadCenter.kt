package ai.rever.boss.downloads

import ai.rever.boss.plugin.api.TransferInfo
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * One in-flight transfer plus the actions only the host can perform.
 *
 * [info] is exactly what a plugin sees through `DownloadCenterProvider`; the two
 * lambdas stay here because they close over host state (a download job, the
 * update installer) that has no meaning across a plugin boundary.
 */
data class Transfer(
    val info: TransferInfo,
    val onCancel: (() -> Unit)? = null,
    val onInstall: (() -> Unit)? = null,
)

/**
 * Every long-running transfer in the app, in one place: plugin installs and
 * updates (started by the host's own prompts or by the Toolbox), and the BOSS
 * application update.
 *
 * This is the single source the bottom-bar progress item and its dialog read,
 * which is the point of it - before it existed, the only visible progress was
 * a widget the Toolbox plugin owned, so every download the host itself started
 * happened silently.
 *
 * Not to be confused with the browser's file downloads (`DownloadDataProvider`
 * and the `downloads` plugin). This one is about plugin jars and the app.
 *
 * Process-wide, like [ai.rever.boss.updater.UpdateManager]: a transfer outlives
 * the window whose button started it, and every window's status bar shows the
 * same set.
 */
object DownloadCenter {
    /** The id the application's own update is tracked under. */
    const val APP_UPDATE_ID = "boss-app-update"

    /**
     * Where the per-plugin views of [transfers] are kept alive.
     *
     * The center's own, and NOT a plugin scope: `DefaultPlugin` is created per WINDOW
     * and its `dispose()` cancels `pluginScope`, so a view built on the first window's
     * scope froze - permanently, and for every plugin that resolved through it - the
     * moment that window closed. This object is process-wide; so is this.
     *
     * Unconfined, so a derived view updates on the thread that changed the center
     * rather than after a dispatch. The derivation is a list map, and the alternative
     * hands a caller reading `transfers.value` right after its own `begin` the value
     * from before it - which is exactly the question `.value` gets asked ("is this one
     * busy?"), and is why these views are eager in the first place.
     */
    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    // Insertion-ordered: rows must not jump around in the dialog as progress ticks.
    private val _transfers = MutableStateFlow<List<Transfer>>(emptyList())
    val transfers: StateFlow<List<Transfer>> = _transfers.asStateFlow()

    /**
     * Start tracking [id], or join the entry already tracking it.
     *
     * There is deliberately no `onInstall` here: an Install action only exists
     * once something has finished downloading, which is a later fact about a row
     * that already exists. [setActions] is where it arrives.
     *
     * @return true when this call created the entry. A caller that gets false is
     *   nested inside an operation someone else owns and must NOT [end] it - the
     *   fallback paths inside an install would otherwise tear down the row their
     *   caller is still using.
     */
    fun begin(
        id: String,
        title: String,
        kind: TransferKind,
        detail: String? = null,
        onCancel: (() -> Unit)? = null,
        owner: String? = null,
    ): Boolean {
        var created = false
        _transfers.update { list ->
            // ASSIGNED on every pass, never only set: `update` is a compare-and-set
            // retry loop, so a caller that built the row and then lost the CAS runs
            // again with the row present - and a stale `true` would hand ownership to
            // two callers, whose `finally` blocks then delete each other's rows.
            val existing = list.firstOrNull { it.info.id == id }
            created = existing == null
            if (existing != null) {
                // Joining is for a fallback path nested inside ONE operation, where the
                // row's Cancel belongs to the operation still running it. Two
                // INDEPENDENT operations can also land here - the host keys plugin work
                // by pluginId, so an "Install Store Version" concurrent with a
                // dependency install of the same id shares a row - and there the row's
                // Cancel would abandon whichever got there first while the user was
                // looking at the other. Withdrawing it is the honest answer: neither
                // can be cancelled without the other, so nothing offers to.
                val joinedByAnother = onCancel != null && existing.onCancel !== onCancel
                if (joinedByAnother) {
                    list.map { t ->
                        if (t.info.id == id) t.copy(onCancel = null, info = t.info.copy(cancellable = false)) else t
                    }
                } else {
                    list
                }
            } else {
                list +
                    Transfer(
                        info =
                            TransferInfo(
                                id = id,
                                title = title,
                                detail = detail,
                                kind = kind,
                                phase = TransferPhase.PREPARING,
                                progress = null,
                                cancellable = onCancel != null,
                                owner = owner,
                            ),
                        onCancel = onCancel,
                    )
            }
        }
        return created
    }

    /**
     * Report download progress for [id]. Also moves the transfer into
     * [TransferPhase.DOWNLOADING]: bytes arriving is what that phase means, and
     * making it implicit means no caller can report progress on a row that still
     * reads "Preparing" (and so refuses to offer Cancel).
     */
    fun progress(
        id: String,
        fraction: Float,
    ) = mutate(id) { t ->
        t.copy(
            info =
                t.info.copy(
                    phase = TransferPhase.DOWNLOADING,
                    // Indeterminate rather than clamped for a non-finite value:
                    // `coerceIn` propagates NaN, and NaN is what `bytes / length` gives
                    // when the length is absent or zero - so it would reach the bar.
                    progress = fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f),
                ),
        )
    }

    /** Move [id] to [phase], dropping the progress fraction once it stops downloading. */
    fun phase(
        id: String,
        phase: TransferPhase,
    ) = mutate(id) { t ->
        t.copy(
            info =
                t.info.copy(
                    phase = phase,
                    progress = if (phase == TransferPhase.DOWNLOADING) t.info.progress else null,
                ),
        )
    }

    /** Replace [id]'s detail line, or clear it. */
    fun detail(
        id: String,
        text: String?,
    ) = mutate(id) { it.copy(info = it.info.copy(detail = text)) }

    /** Replace [id]'s actions, e.g. when a finished download gains an Install. */
    fun setActions(
        id: String,
        onCancel: (() -> Unit)?,
        onInstall: (() -> Unit)?,
    ) = mutate(id) { it.copy(onCancel = onCancel, onInstall = onInstall) }

    /** Stop tracking [id]. Idempotent, so it is safe in a `finally`. */
    fun end(id: String) {
        _transfers.update { list -> list.filterNot { it.info.id == id } }
    }

    /**
     * Run [id]'s cancel action once, if it currently has one.
     *
     * Read and cleared in one update, so a second press cannot fire it again. The row
     * survives a cancel - the work being cancelled removes it through the same
     * `finally` a completed one uses, which can take a while on a slow socket - so
     * without this the button stays clickable over a live-looking bar.
     *
     * A caller that re-asserts its actions per state (the app-update mirror does, on
     * every progress tick) puts the action back, which is right: while bytes are still
     * arriving the transfer really is still cancellable, and the underlying
     * `cancelDownload` is idempotent. What this closes is the double click, where two
     * presses land before anything has moved.
     */
    fun cancel(id: String) {
        var action: (() -> Unit)? = null
        _transfers.update { list ->
            val target = list.firstOrNull { it.info.id == id }
            // Assigned on every pass: `update` retries, and a stale action from a lost
            // CAS would run after another thread had already taken it.
            action = target?.onCancel?.takeIf { target.info.cancellable }
            if (action == null) {
                list
            } else {
                list.map { t ->
                    if (t.info.id == id) t.copy(onCancel = null, info = t.info.copy(cancellable = false)) else t
                }
            }
        }
        action?.invoke()
    }

    /**
     * Run [id]'s install action once, if it has one (a downloaded app update).
     *
     * Single-shot for a sharper reason than Cancel: the action is withdrawn only when
     * the state reaches Installing, which is a background hop plus a collect plus a
     * recomposition away, and the row still reads "Ready to install" under the cursor.
     * Two quick clicks used to launch two elevated installs of the same artifact.
     */
    fun install(id: String) {
        var action: (() -> Unit)? = null
        _transfers.update { list ->
            val target = list.firstOrNull { it.info.id == id }
            action = target?.onInstall
            if (action == null) {
                list
            } else {
                list.map { t -> if (t.info.id == id) t.copy(onInstall = null) else t }
            }
        }
        action?.invoke()
    }

    /** Drop everything. Test-only; production rows are ended by their owners. */
    internal fun reset() {
        _transfers.value = emptyList()
    }

    /**
     * Apply [block] to [id] and re-derive `cancellable` from the result, so that
     * flag has exactly one definition: an action exists AND the transfer is not
     * being installed.
     *
     * [TransferPhase.INSTALLING] is the one phase that cannot be abandoned -
     * cancelling a jar swap mid-flight leaves the plugin unloaded, and an app
     * install is file moves plus an elevated helper. Everything else can:
     * a download is bytes, and a downloaded-but-uninstalled app update is a file
     * to delete, which is exactly the "Install / Cancel" pair the dialog offers.
     */
    private fun mutate(
        id: String,
        block: (Transfer) -> Transfer,
    ) {
        _transfers.update { list ->
            list.map { t ->
                if (t.info.id != id) {
                    t
                } else {
                    val next = block(t)
                    next.copy(
                        info =
                            next.info.copy(
                                cancellable = next.onCancel != null && next.info.phase != TransferPhase.INSTALLING,
                            ),
                    )
                }
            }
        }
    }
}
