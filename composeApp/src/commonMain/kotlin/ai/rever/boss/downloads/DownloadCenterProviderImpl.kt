package ai.rever.boss.downloads

import ai.rever.boss.plugin.api.DownloadCenterProvider
import ai.rever.boss.plugin.api.TransferHandle
import ai.rever.boss.plugin.api.TransferInfo
import ai.rever.boss.plugin.api.TransferKind
import ai.rever.boss.plugin.api.TransferPhase
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.util.concurrent.ConcurrentHashMap

/**
 * The plugin-facing view of [DownloadCenter].
 *
 * Plain common code rather than the `createXxxProvider` expect/actual other
 * providers use: there is nothing platform-specific to reach for, the center is
 * already common, and an expect/actual pair here would only be ceremony.
 *
 * The derived flow lives on [DownloadCenter.scope] - the center's own, because a
 * plugin-layer scope belongs to one window and dies with it.
 */
class DownloadCenterProviderImpl internal constructor(
    /**
     * Prefix for ids this instance reports, or null for the host's own transfers.
     *
     * Without it the id space is one unnamespaced namespace shared by the host and
     * every plugin, which is two bugs rather than one: two plugins both choosing
     * `"update"` collide and hit the nested-begin rule by accident (the second is
     * bound to a row it cannot remove, and its transfer never appears), and any
     * plugin can address another's transfer - or the host's app update - to withdraw
     * its Cancel, fake its progress, or fabricate a row. The prefix is stripped on
     * the way out, so a plugin still matches its own work by the id it passed in.
     */
    private val idPrefix: String? = null,
) : DownloadCenterProvider {
    override val transfers: StateFlow<List<TransferInfo>> =
        DownloadCenter.transfers
            .map { list -> list.map { it.info.withoutPrefix(idPrefix) } }
            .stateIn(
                // The center's scope, not a caller's: see DownloadCenter.scope for the
                // window-lifetime trap that hid behind "a long-lived scope".
                scope = DownloadCenter.scope,
                // Eagerly, so `.value` is truthful for a reader that never collects -
                // WhileSubscribed would hand such a caller the stale initial value, and
                // "is this one busy?" is exactly a `.value` question. The instance is
                // cached per prefix (see [forPlugin]), so this costs one collector per
                // plugin rather than one per plugin LOAD.
                started = SharingStarted.Eagerly,
                initialValue = DownloadCenter.transfers.value.map { it.info.withoutPrefix(idPrefix) },
            )

    override fun begin(
        id: String,
        title: String,
        kind: TransferKind,
        detail: String?,
        onCancel: (() -> Unit)?,
    ): TransferHandle {
        val key = qualify(id)
        // The owner is this instance's prefix: null for the host's own view, the
        // plugin id otherwise. It is what lets a plugin tell its row for `com.foo`
        // from the host's row for `com.foo`, which the id alone cannot.
        val created = DownloadCenter.begin(key, title, kind, detail, onCancel, owner = idPrefix)
        return CenterHandle(key, ownsEntry = created)
    }

    private fun qualify(id: String): String = idPrefix?.let { "$it:$id" } ?: id

    /**
     * Show a plugin its own ids unprefixed, and leave everyone else's alone.
     *
     * A plugin asking "is this one busy?" compares against the id it passed to
     * [begin]; it should not have to know the host qualified it. Other plugins' rows
     * stay qualified, which is what makes them unmistakable for your own.
     */
    private fun TransferInfo.withoutPrefix(prefix: String?): TransferInfo {
        val head = prefix?.plus(":") ?: return this
        return if (id.startsWith(head)) copy(id = id.removePrefix(head)) else this
    }

    companion object {
        private val byPrefix = ConcurrentHashMap<String, DownloadCenterProviderImpl>()

        /**
         * The instance for [pluginId], created once and reused.
         *
         * A fresh `TrackingPluginContext` is built on every plugin LOAD, so constructing
         * one of these per context left an eager collector behind on each hot reload,
         * Toolbox reload and `resetPluginInstances` - growing with reload count rather
         * than plugin count, each survivor remapping the whole transfer list per tick.
         *
         * Safe to cache because the view lives on [DownloadCenter.scope], which nothing
         * cancels - a window closing or a plugin unloading cannot freeze another
         * plugin's view.
         */
        fun forPlugin(pluginId: String) = byPrefix.computeIfAbsent(pluginId) { DownloadCenterProviderImpl(it) }

        /** The host's own view: unprefixed ids, one instance. */
        fun forHost(): DownloadCenterProviderImpl = hostView

        private val hostView by lazy { DownloadCenterProviderImpl(idPrefix = null) }
    }

    /**
     * A handle over one center entry.
     *
     * [ownsEntry] is the nested-begin rule: a caller that joined an existing
     * transfer reports into it but must not remove it, or an inner fallback path
     * would tear down the row its caller is still filling.
     */
    private class CenterHandle(
        private val id: String,
        private val ownsEntry: Boolean,
    ) : TransferHandle {
        override fun progress(fraction: Float) = DownloadCenter.progress(id, fraction)

        override fun phase(phase: TransferPhase) = DownloadCenter.phase(id, phase)

        override fun detail(text: String?) = DownloadCenter.detail(id, text)

        override fun done() {
            if (ownsEntry) DownloadCenter.end(id)
        }
    }
}
