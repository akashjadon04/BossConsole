package ai.rever.boss.plugin.ui.menu

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import java.awt.AWTEvent
import java.awt.Dialog
import java.awt.Frame
import java.awt.KeyboardFocusManager
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import java.awt.event.AWTEventListener
import java.awt.event.MouseEvent
import java.awt.image.BufferedImage
import javax.swing.SwingUtilities

/**
 * `java.awt.PopupMenu`, which on macOS is peered by `sun.lwawt.macosx.CPopupMenu` onto a real
 * `NSMenu` (`CMenu.getNativeMenu()` hands back the `NSMenu*`, and `libawt_lwawt.dylib` carries
 * `popUpMenuPositioningItem:atLocation:inView:`).
 *
 * Four behaviours were **measured** with a throwaway harness rather than read out of the JDK
 * sources, and each one shapes the code below. Do not re-derive them:
 *
 * 1. `show()` does **not** block. It returns in ~0 ms and the EDT stays live, so Compose keeps
 *    painting while the menu is up. It also means its return is not a dismissal signal.
 * 2. **Nothing cancels an open menu.** `setVisible(false)`, `remove()` and `dispose()` on the
 *    invoker all leave it tracking and selectable. So [hide] cannot dismiss; correctness rests on
 *    the generation fence, which makes a lingering menu's items inert.
 * 3. **There is no dismissal event on any AWT mask.** But an open menu holds the input grab and
 *    lets nothing through (dragging the pointer across it, and pressing and releasing a modifier,
 *    both delivered zero events), while dismissal produces an immediate burst: `MOUSE_EXITED`,
 *    the Escape key-release, then `MOUSE_ENTERED`. So "clear on the next input event" cannot fire
 *    early and fires promptly.
 * 4. `MenuShortcut` is **display-only** - no live key equivalent, so glyphs cannot double-fire
 *    with the app's own accelerators. Menu items **can** be disabled while the menu is open, and
 *    a disabled item does not activate; that is what lets [hide] grey an orphan.
 *
 * Fact 3 is specifically an NSMenu-tracking property and is the first thing to re-measure if
 * [shouldUseNativeMenus] ever widens past macOS.
 */
actual object NativeContextMenus {
    private val presenter = AwtPopupPresenter()

    actual fun isSupported(): Boolean = OsFamily.isMac

    actual fun show(
        nodes: List<NativeMenuNode>,
        anchor: NativeMenuAnchor,
        onDismiss: () -> Unit,
    ): Boolean = if (isSupported()) presenter.show(nodes, anchor, onDismiss) else false

    actual fun hide() = presenter.hide()

    // Re-exported for tests: the dismissal heuristic is the least obvious thing in this file and
    // the one most likely to need re-measuring if the platform gate ever widens.
    internal const val DISMISS_GRACE_MS = DismissWatcher.DISMISS_GRACE_MS

    internal fun isDismissalEvent(
        eventId: Int,
        elapsedMs: Long,
    ): Boolean = DismissWatcher.isDismissalEvent(eventId, elapsedMs)
}

/** Cached once: `os.name` cannot change while the process runs. */
internal object OsFamily {
    private val name: String = System.getProperty("os.name")?.lowercase().orEmpty()
    val isMac: Boolean = name.contains("mac")
    val isWindows: Boolean = name.contains("windows")
}

/**
 * Owns the one popup that can be on screen at a time, plus the state that makes a lingering menu
 * safe. Separate from the facade so the AWT mechanics are not mixed into the public surface.
 */
internal class AwtPopupPresenter {
    // EDT-only: show() declines off the EDT and hide() posts.
    @Volatile
    private var attached: Pair<Window, java.awt.PopupMenu>? = null
    private val watcher = DismissWatcher()

    /**
     * Bumped on every show and on [hide]. Item actions are fenced on the generation they were
     * built for and no-op once ownership has moved on - which is what actually protects against a
     * menu outliving the UI that opened it and then acting on state that has gone away.
     */
    @Volatile
    private var generation: Long = 0L

    // Guard clauses, each declining for a different reason. Flattening them into one nested
    // expression would obscure exactly what this function exists to make clear: every path that
    // cannot produce a menu must say so, so the caller can draw its own.
    @Suppress("ReturnCount")
    fun show(
        nodes: List<NativeMenuNode>,
        anchor: NativeMenuAnchor,
        onDismiss: () -> Unit,
    ): Boolean {
        // Decided synchronously, because the caller uses the return value to choose between this
        // and its own menu. Posting the work would mean reporting success before knowing whether
        // a menu appears, and a later failure would then leave the right-click doing nothing at
        // all - the outcome the fallback exists to prevent. Callers are on the EDT (Compose's
        // main dispatcher); off it, decline.
        val planned = planNativeMenu(nodes, OsFamily.isWindows)
        if (!canShowNatively(
                isSupported = true,
                isEventDispatchThread = SwingUtilities.isEventDispatchThread(),
                plannedSize = planned.size,
            )
        ) {
            return false
        }

        val at = anchor.resolveScreenPoint()
        val invoker = resolveInvoker(at) ?: return false

        generation += 1
        val shown = generation
        val isCurrent = { generation == shown }

        run {
            // A PopupMenu must hang off a live Component and AWT keeps it as a child until
            // removed, so detach the previous one rather than accumulating menus on the window.
            // Grey it out first: per measured fact 2 the outgoing NSMenu may still be tracking on
            // screen, and once detached hide() has no handle left to disable it with. The fence
            // already makes its items inert, but a menu that looks live and does nothing reads as
            // a hang. Reachable via the keyboard menu key, which does not consume the grab.
            attached?.let { (_, menu) -> runCatching { disableAll(menu) } }
            detach()

            val popup = java.awt.PopupMenu()
            // Tear down only what THIS menu installed. `materialize` runs an item's action BEFORE
            // dismissing, so an action that opens another menu would otherwise have its
            // successor's popup and watcher torn down underneath it, and the generation fence
            // would then swallow the successor's dismissal.
            var myWatcher: AWTEventListener? = null
            val dismissed = {
                detachIf(popup)
                watcher.clearIf(myWatcher)
                onDismiss()
            }
            val pendingIcons = mutableListOf<Pair<java.awt.MenuItem, ImageBitmap>>()
            materialize(popup, planned, isCurrent, dismissed, pendingIcons)
            invoker.add(popup)
            attached = invoker to popup
            // Peers only exist once the menu is realised by add(), and setImage works before
            // show(), so this is the one window where icons can be applied.
            pendingIcons.forEach { (item, icon) -> MenuItemIcons.apply(item, icon) }

            val local =
                at.toInvokerCoordinates(invoker) ?: run {
                    detach()
                    return false
                }
            // The one call left that can still fail. An escaping exception would leave the caller
            // believing a menu is up when none is, so decline and let it draw its own.
            if (runCatching { popup.show(invoker, local.x, local.y) }.isFailure) {
                detach()
                return false
            }
            // Armed after show() (which returns in ~0 ms) so the grace window covers only the gap
            // before the OS takes the input grab, not the invoker resolution before it.
            myWatcher = watcher.install(dismissed)
            // No watcher means no dismissal signal will ever arrive. A caller left believing the
            // menu is still up is the worse direction, so report dismissal now.
            if (myWatcher == null) dismissed()
        }
        return true
    }

    fun hide() {
        // Only invalidate while a menu is actually attached. hide() routinely runs from teardown
        // triggered BY the menu dismissing itself (a DisposableEffect's onDispose), and bumping
        // unconditionally would fence off the item's own ActionEvent if it is still queued - so
        // the click the user just made would silently do nothing.
        if (attached != null) generation += 1
        onEdt {
            watcher.clear()
            // Grey out an orphan before letting go of it. The fence already makes it inert, but
            // "clicks and nothing happens" reads as a hang; disabling items on an OPEN menu was
            // measured to be safe and to stop them activating.
            attached?.let { (_, menu) -> runCatching { disableAll(menu) } }
            detach()
        }
    }

    // ---- invoker selection -------------------------------------------------------------------

    private fun NativeMenuAnchor.resolveScreenPoint(): Point? =
        when (this) {
            is NativeMenuAnchor.Screen -> {
                Point(x, y)
            }

            NativeMenuAnchor.Cursor -> {
                runCatching { MouseInfo.getPointerInfo()?.location }.getOrNull()
            }
        }

    /** Null when the origin is unknowable - better to decline than to guess the window corner. */
    private fun Point?.toInvokerCoordinates(invoker: Window): Point? {
        val origin = runCatching { invoker.locationOnScreen }.getOrNull()
        val screen = this
        return if (origin == null || screen == null) {
            null
        } else {
            Point(screen.x - origin.x, screen.y - origin.y)
        }
    }

    private fun resolveInvoker(at: Point?): Window? {
        // The focused window is only a shortcut if it satisfies what pickInvoker would demand of
        // it. With several windows open - a main frame, Settings, a detached browser - the
        // focused one need not contain the click, and using it anyway subtracts the wrong origin
        // and puts the menu far from the pointer. It must also be a real frame or dialog, since
        // getWindows() returns the heavyweight windows Swing creates for popups.
        KeyboardFocusManager
            .getCurrentKeyboardFocusManager()
            .focusedWindow
            ?.takeIf { it is Frame || it is Dialog }
            ?.takeIf { it.isShowing && (at == null || it.bounds.contains(at)) }
            ?.let { return it }

        val candidates =
            runCatching { Window.getWindows() }
                .getOrNull()
                ?.map {
                    InvokerCandidate(
                        window = it,
                        isFrameOrDialog = it is Frame || it is Dialog,
                        isShowing = it.isShowing,
                        isActive = it.isActive,
                        bounds = it.bounds,
                    )
                }.orEmpty()

        // Deliberately no toFront()/requestFocus(): PopupMenu.show does not need an active
        // invoker, and reordering the window stack from a resolve step would let a right-click
        // raise a window over whatever the user had in front.
        return pickInvoker(candidates, at)?.window
    }

    // ---- rendering ---------------------------------------------------------------------------

    private fun materialize(
        menu: java.awt.Menu,
        nodes: List<NativeMenuNode>,
        isCurrent: () -> Boolean,
        onDismiss: () -> Unit,
        pendingIcons: MutableList<Pair<java.awt.MenuItem, ImageBitmap>>,
    ) {
        nodes.forEach { node ->
            when (node) {
                is NativeMenuNode.Separator -> {
                    menu.addSeparator()
                }

                is NativeMenuNode.Item -> {
                    menu.add(node.toAwtItem(isCurrent, onDismiss, pendingIcons))
                }

                is NativeMenuNode.Submenu -> {
                    menu.add(
                        java.awt.Menu(node.label).also {
                            materialize(it, node.children, isCurrent, onDismiss, pendingIcons)
                        },
                    )
                }
            }
        }
    }

    /** getItem returns the `java.awt.Menu` for a submenu, so recurse to reach its children. */
    private fun disableAll(menu: java.awt.Menu) {
        for (i in 0 until menu.itemCount) {
            val item = menu.getItem(i)
            item.isEnabled = false
            if (item is java.awt.Menu) disableAll(item)
        }
    }

    private fun detach() {
        attached?.let { (owner, menu) -> runCatching { owner.remove(menu) } }
        attached = null
    }

    private fun detachIf(popup: java.awt.PopupMenu) {
        if (attached?.second === popup) detach()
    }

    private fun onEdt(block: () -> Unit) {
        if (SwingUtilities.isEventDispatchThread()) block() else SwingUtilities.invokeLater(block)
    }
}

/**
 * Knows when a native menu has closed.
 *
 * Its own class because inferring dismissal is a wholly separate concern from putting a popup on
 * screen, and it owns process-wide state (an AWT-wide listener) that must be cleaned up exactly.
 */
private class DismissWatcher {
    private var current: AWTEventListener? = null

    /**
     * Infer dismissal from the next input event, since AWT reports none (fact 3 above).
     *
     * `WINDOW_EVENT_MASK` is included because dismissing by switching applications produces no
     * input event at all, which would otherwise leave the caller believing the menu is still up.
     *
     * Only [AWTEvent.getID] is inspected; no event contents are read. That matters because this
     * is a process-wide listener installed by a library that runs inside a host application.
     */
    fun install(onDismiss: () -> Unit): AWTEventListener? {
        clear()
        val armedAt = System.currentTimeMillis()
        val toolkit = runCatching { Toolkit.getDefaultToolkit() }.getOrNull() ?: return null
        val listener =
            object : AWTEventListener {
                override fun eventDispatched(event: AWTEvent) {
                    if (!isDismissalEvent(event.id, System.currentTimeMillis() - armedAt)) return
                    runCatching { toolkit.removeAWTEventListener(this) }
                    if (current === this) current = null
                    onDismiss()
                }
            }
        current = listener
        // Requires AWTPermission("listenToAllAWTEvents"). If a host's policy refuses, lose the
        // dismissal signal rather than the menu.
        return runCatching {
            toolkit.addAWTEventListener(
                listener,
                AWTEvent.MOUSE_EVENT_MASK or AWTEvent.MOUSE_MOTION_EVENT_MASK or
                    AWTEvent.KEY_EVENT_MASK or AWTEvent.WINDOW_EVENT_MASK,
            )
            listener
        }.getOrElse {
            current = null
            null
        }
    }

    fun clear() {
        current?.let {
            runCatching { Toolkit.getDefaultToolkit().removeAWTEventListener(it) }
        }
        current = null
    }

    fun clearIf(listener: AWTEventListener?) {
        if (listener != null && current === listener) clear()
    }

    companion object {
        const val DISMISS_GRACE_MS = 120L

        /**
         * Whether an AWT input event means the menu has closed.
         *
         * Two independent guards, both against mistaking the *opening* right-click's own tail for
         * a dismissal: nothing inside the grace window counts, since `show()` is posted to the EDT
         * and the OS grab is not established the instant it returns; and `MOUSE_RELEASED` never
         * counts, because wall-clock alone is not enough - under a busy EDT that release can be
         * dispatched well after the window expires. Nothing is lost by filtering it, since the
         * measured dismissal burst is `MOUSE_EXITED` / key-release / `MOUSE_ENTERED` and a
         * click-away has its press swallowed by the menu, so a release is never the only signal.
         */
        fun isDismissalEvent(
            eventId: Int,
            elapsedMs: Long,
        ): Boolean = elapsedMs >= DISMISS_GRACE_MS && eventId != MouseEvent.MOUSE_RELEASED
    }
}

/** The properties [pickInvoker] ranks on, lifted off AWT so the rule can be tested headlessly. */
internal data class InvokerCandidate<T>(
    val window: T,
    val isFrameOrDialog: Boolean,
    val isShowing: Boolean,
    val isActive: Boolean,
    val bounds: Rectangle,
)

/**
 * Pick the AWT window to hang a popup off.
 *
 * `Window.getWindows()` is not in z-order and also returns the heavyweight windows Swing creates
 * for popups, so it is filtered to real frames and dialogs. Smallest-area-first is a proxy for
 * topmost: a popup is owned by its invoker, so choosing the window underneath would place the
 * menu behind the one on top.
 */
internal fun <T> pickInvoker(
    candidates: List<InvokerCandidate<T>>,
    at: Point?,
): InvokerCandidate<T>? =
    candidates
        .filter { it.isFrameOrDialog && it.isShowing }
        // Rectangle.contains is half-open on the right/bottom edges, so a click on the very edge can
        // match nothing. Falling back to all eligible windows keeps the mild degradation (menu on the
        // wrong window) rather than introducing a worse one (right-click silently does nothing).
        .let { eligible ->
            eligible.filter { at == null || it.bounds.contains(at) }.ifEmpty { eligible }
        }.minWithOrNull(
            compareByDescending<InvokerCandidate<T>> { it.isActive }
                .thenBy { it.bounds.width.toLong() * it.bounds.height.toLong() },
        )

/** Build the AWT item for one node, queueing its icon for after the peer exists. */
private fun NativeMenuNode.Item.toAwtItem(
    isCurrent: () -> Boolean,
    onDismiss: () -> Unit,
    pendingIcons: MutableList<Pair<java.awt.MenuItem, ImageBitmap>>,
): java.awt.MenuItem {
    val node = this
    val item =
        java.awt.MenuItem(node.label).apply {
            isEnabled = node.enabled
            node.shortcut?.let { setShortcut(java.awt.MenuShortcut(it.code)) }
            addActionListener {
                // finally, because the action is invokeAttributed, which rethrows by design.
                // Without this the popup stays attached, the watcher stays installed and the
                // caller never learns the menu closed - so its state says a menu is up while the
                // OS menu is already gone, and no further right-click can reopen it.
                try {
                    if (isCurrent()) node.action()
                } finally {
                    runCatching { onDismiss() }
                }
            }
        }
    node.icon?.let { pendingIcons += item to it }
    return item
}

/**
 * Puts an icon on a native menu item.
 *
 * `java.awt.MenuItem` has no icon API, but its macOS peer does: `sun.lwawt.macosx.CMenuItem`
 * declares `public void setImage(Image)`, and the JDK's own comment on it says the intended
 * access is an `instanceof` on the peer, because "we want to support the NSMenuItem image apis".
 *
 * The peer is reached through `sun.awt.AWTAccessor$MenuComponentAccessor`, deliberately resolving
 * the method on the **public interface** rather than on the accessor's implementation class. The
 * implementation is an anonymous class in `java.awt`, so going through it would need
 * `--add-opens java.desktop/java.awt`, which this app does not set. Through the interface, the
 * `sun.awt` and `sun.lwawt.macosx` opens it already has on macOS are enough. Verified both ways.
 *
 * Everything is best-effort: this is private JDK API, so a failure drops the icon and keeps the
 * menu rather than taking the right-click down.
 */
private object MenuItemIcons {
    private val peerAccessor: Pair<Any, java.lang.reflect.Method>? by lazy {
        runCatching {
            val accessor =
                Class
                    .forName("sun.awt.AWTAccessor")
                    .getMethod("getMenuComponentAccessor")
                    .invoke(null)
            val getPeer =
                Class
                    .forName("sun.awt.AWTAccessor\$MenuComponentAccessor")
                    .getMethod("getPeer", java.awt.MenuComponent::class.java)
            accessor!! to getPeer
        }.getOrNull()
    }

    fun apply(
        item: java.awt.MenuItem,
        icon: ImageBitmap,
    ) {
        runCatching {
            val (accessor, getPeer) = peerAccessor ?: return
            val peer = getPeer.invoke(accessor, item) ?: return
            val setImage = peer.javaClass.getMethod("setImage", java.awt.Image::class.java)
            setImage.invoke(peer, icon.toAwtImage().asMenuIcon())
        }
    }

    /**
     * State the icon's size in POINTS, not pixels.
     *
     * `CImage.createFromImage` branches on `MultiResolutionImage`: given one, it builds an NSImage
     * from the variants, and the base variant's dimensions become the image's point size. Given a
     * plain bitmap it instead uses the raw pixel dimensions as the point size, so a 2x bitmap
     * renders at twice the intended size - which is exactly what a Retina rasterisation produced
     * before this existed.
     */
    private fun BufferedImage.asMenuIcon(): java.awt.Image {
        val points = NATIVE_MENU_ICON_POINTS
        if (width == points) return this
        val base = BufferedImage(points, points, BufferedImage.TYPE_INT_ARGB)
        base.createGraphics().apply {
            setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            drawImage(this@asMenuIcon, 0, 0, points, points, null)
            dispose()
        }
        return java.awt.image.BaseMultiResolutionImage(base, this)
    }
}
