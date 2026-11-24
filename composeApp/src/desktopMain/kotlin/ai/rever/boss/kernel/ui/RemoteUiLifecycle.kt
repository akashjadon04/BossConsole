package ai.rever.boss.kernel.ui

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import ai.rever.boss.ui.sdk.LifecycleStates
import ai.rever.boss.ui.sdk.UIEventMapper
import ai.rever.boss.ui.sdk.WidgetEvent

/**
 * `created` / `destroyed` for a remote surface — the half of the lifecycle family that can actually be
 * delivered, and nothing else.
 *
 * ## What the events mean
 *
 * **A surface's rendered lifetime**, not a plugin's. `created` says "a host renderer is attached and
 * your stream is live — your widgets are on screen"; `destroyed` says that stopped being true. That is
 * the thing a plugin cannot work out for itself and the thing it needs in order to start and stop
 * doing work; whereas "my process registered a surface" is something it already knows, having done it.
 *
 * The pairing is a **rendezvous between two independent lifetimes**, so it is announced by whichever
 * half completes it — the component attaching to a streaming surface, or a stream binding under an
 * already-attached component — and the latch below makes that exactly once either way. It re-arms on
 * teardown, so a plugin that crashes and respawns under a component that never went away is told
 * `created` again: the new process never heard the first one.
 *
 * ## Why `destroyed` is deliverable after all
 *
 * #34 deferred this family because "`destroyed` cannot be flushed: the outgoing flow dies with the
 * surface scope", and a create-without-destroy lifecycle is worse than none. That was true of a flush
 * *after* teardown. It is not true of an ordinary send *before* one: `RemoteUiSurface.close()` calls
 * `Channel.close()`, which is graceful — already-buffered elements are handed to the collector and
 * *then* the flow completes. So enqueueing `destroyed` and immediately closing delivers it, as the
 * last event the plugin sees, with no drain phase and no new API.
 *
 * Every teardown path that still has a transport therefore announces it: a host component detaching,
 * the plugin's own `UnregisterUI`, and kernel shutdown.
 *
 * ## The one path that cannot, and why that is not a hole
 *
 * A **dead stream** — `RemoteUiSurfaceRegistry.closeStream` after a plugin crashed or its channel
 * dropped. There is nothing to deliver over and nobody to receive it. Stream completion is the signal
 * there, exactly as #34 anticipated: the plugin's own event flow ending *is* the notice, and a process
 * that has died does not need one. So the guarantee this file offers is precise rather than absolute:
 * **a plugin that can be told, is told**.
 *
 * ## The limit of the pairing
 *
 * The latch pairs the two events **at enqueue**. Delivery is the outgoing channel's business, and that
 * channel sheds its **oldest** entry when a plugin stops reading (`RemoteUiSurface.OUTGOING_BUFFER`) —
 * and `created` is by construction the oldest thing a rendered surface ever queues. A plugin that stops
 * reading for long enough to shed its own first [RemoteUiSurface.OUTGOING_BUFFER] events can therefore
 * lose its `created` and later receive a `destroyed` on its own. Narrow, and self-announcing:
 * [RemoteUiSurface.shedEventCount] is non-zero exactly when it is possible. Documented rather than
 * engineered around, because the alternative is a second delivery path for two events per surface.
 */
internal object RemoteUiLifecycle {
    /**
     * Announce that [surface] is now rendered. No-op if it already was.
     *
     * @return whether an event was queued — `false` means it was already announced, or the surface is
     *   closed. Returned rather than logged so the registry's call sites stay one line and tests can
     *   assert the latch directly.
     */
    fun announceCreated(surface: RemoteUiSurface): Boolean =
        synchronized(surface.createdAnnounced) {
            if (!surface.createdAnnounced.compareAndSet(false, true)) return@synchronized false
            val queued = surface.emitLifecycle(LifecycleStates.CREATED)
            // Roll the latch back rather than leave the surface owing a `destroyed` for a `created` no
            // plugin saw. Under the monitor, not just a CAS: an unsynchronized rollback leaves a window
            // between the failed emit and the reset in which a concurrent announceDestroyed can take the
            // latch and emit an unmatched teardown — the very asymmetry the latch exists to prevent.
            if (!queued) surface.createdAnnounced.set(false)
            queued
        }

    /**
     * Announce that [surface] is no longer rendered. No-op unless [announceCreated] fired first.
     *
     * The CAS is what makes the pair symmetric by construction instead of by discipline: there is no
     * ordering of attach, detach, register, unregister and shutdown that can produce a `destroyed`
     * with no `created`, or two of either. Reset rather than set, so a respawn under the same
     * component announces afresh.
     */
    fun announceDestroyed(surface: RemoteUiSurface): Boolean =
        // Same monitor as announceCreated, so the two transitions are ordered against each other rather
        // than merely each being atomic on their own.
        synchronized(surface.createdAnnounced) {
            surface.createdAnnounced.compareAndSet(true, false) &&
                surface.emitLifecycle(LifecycleStates.DESTROYED)
        }

    /**
     * Queue one lifecycle event on the surface's outgoing stream.
     *
     * Surface-level, so the node id is empty — a lifecycle transition belongs to the surface and not
     * to any node in its tree (see `EmittedEvent`).
     */
    private fun RemoteUiSurface.emitLifecycle(state: String): Boolean {
        val event = UIEventMapper.toProto(surfaceId, "", WidgetEvent.Lifecycle(state), System.currentTimeMillis())
        val delivered = emit(event)
        if (!delivered) {
            // Reachable and unremarkable: the surface closed between the latch and here. Debug, because
            // a crash-looping plugin would otherwise write a line per restart.
            logger.debug(
                LogCategory.UI,
                "Surface closed before its lifecycle event could be queued",
                mapOf("surfaceId" to surfaceId, "state" to state),
            )
        }
        return delivered
    }

    private val logger = BossLogger.forComponent("RemoteUiLifecycle")
}
