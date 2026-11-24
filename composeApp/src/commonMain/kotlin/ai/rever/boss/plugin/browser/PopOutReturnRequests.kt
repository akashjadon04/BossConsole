package ai.rever.boss.plugin.browser

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Carries "put me back on this tab" from a detached pop-out to the window that owns the tab.
 *
 * The pop-out's Back-to-tab button lives in `BrowserHandleImpl`, which can raise a window but
 * cannot select a tab: selecting one is `SplitViewState`'s, and the tab itself belongs to the
 * browser **plugin**, whose component type the host cannot even name. The obvious fix - a new
 * callback on `BrowserHandle` for the plugin to register - would work, and costs an api release,
 * a host version pin and a plugin release before anybody sees it, plus a permanent branch for
 * hosts and plugins that predate it.
 *
 * None of that is needed, because the plugin already tells the host its tab id when it registers
 * the fullscreen handler. So the request travels in-process instead: the handle emits, and the
 * window that owns that tab resolves the panel and selects it.
 *
 * A `SharedFlow` rather than a `StateFlow`: this is an event, and two presses of the same button
 * must both arrive rather than the second being swallowed as a duplicate value. Buffered and
 * emitted with `tryEmit` so a press can never suspend the UI thread that raised it, and scoped by
 * window so that with two windows open only the one holding the tab acts.
 */
object PopOutReturnRequests {
    data class Request(
        val windowId: String,
        val tabId: String,
    )

    private val _requests = MutableSharedFlow<Request>(extraBufferCapacity = REQUEST_BUFFER)
    val requests: SharedFlow<Request> = _requests.asSharedFlow()

    /** Asks the window owning [tabId] to bring that tab to the front. */
    fun request(
        windowId: String,
        tabId: String,
    ): Boolean {
        if (windowId.isEmpty() || tabId.isEmpty()) return false
        // The result is returned rather than discarded: a full buffer means a press was dropped,
        // and Back-to-tab doing nothing with no trace is the failure this whole path is prone to.
        // The caller logs it, the way PluginDependencyEventBus logs its own refusals.
        return _requests.tryEmit(Request(windowId, tabId))
    }

    private const val REQUEST_BUFFER = 4
}
