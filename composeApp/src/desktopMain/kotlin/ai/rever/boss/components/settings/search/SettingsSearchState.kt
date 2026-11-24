package ai.rever.boss.components.settings.search

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/**
 * The query, the ranked hits and the keyboard selection, in one holder.
 *
 * It exists because the two halves live in different composables and must not each keep their own
 * copy: the *window* handles the keys (its `onPreviewKeyEvent` is the only place Cmd+F fires
 * regardless of what holds focus) while the *content* computes the hits, since only it collects the
 * plugin pages that go into the index. Passing four `var`s and four setters between them is the
 * version of this that drifts.
 */
@Stable
internal class SettingsSearchState {
    var query by mutableStateOf("")
        private set

    /** Which hit Enter would open. Always a valid index into [hits], or 0 when there are none. */
    var selectedIndex by mutableStateOf(0)
        private set

    /** Written by the content once it has ranked them; read by the key handler. */
    var hits by mutableStateOf<List<SettingsSearchHit>>(emptyList())

    /**
     * Bumped to ask the field to take focus. A counter, not a flag, for the reason
     * `SettingsWindowState.focusRequest` documents at length: pressing Cmd+F twice must focus
     * twice, and assigning `true` to something already `true` is not an event.
     */
    var focusTick by mutableStateOf(0)
        private set

    /** Set by the content, which is the only thing that can act on a hit. */
    var onPick: ((SettingsSearchHit) -> Unit)? = null

    fun updateQuery(value: String) {
        query = value
        // Any edit invalidates the old selection - the list underneath it has changed - and
        // leaving the index where it was would make Enter open whatever happened to land there.
        selectedIndex = 0
    }

    fun clear() = updateQuery("")

    fun requestFocus() {
        focusTick++
    }

    /** Clamps rather than wraps: an arrow key that jumps from the last hit to the first reads as a bug. */
    fun moveSelection(delta: Int) {
        if (hits.isEmpty()) return
        selectedIndex = (selectedIndex + delta).coerceIn(0, hits.size - 1)
    }

    fun openSelected() {
        hits.getOrNull(selectedIndex)?.let { onPick?.invoke(it) }
    }
}

/**
 * Window-level key handling for the Settings search field.
 *
 * Hung on the `Window` rather than on a `Surface` because Cmd+F has to work when nothing inside has
 * focus, and a key event only reaches an ancestor handler if some descendant is focused. Everything
 * except the find chord is gated on a non-blank query, so Escape, the arrows and Enter behave
 * normally in every other part of the window.
 *
 * This does not collide with the `browser.find` binding on Cmd+F: that is
 * `ShortcutContext.BROWSER`, and the keymap interceptor resolves by window id, which this window
 * does not have.
 */
internal fun handleSettingsSearchKey(
    event: KeyEvent,
    state: SettingsSearchState,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false

    val isFindChord = event.key == Key.F && (event.isMetaPressed || event.isCtrlPressed)

    // Everything but the find chord is gated on an active query. Consuming Escape or Enter while
    // nobody is searching would stop dialogs closing, and stop every text field on every settings
    // page accepting a return.
    return when {
        isFindChord -> {
            state.requestFocus()
            true
        }

        state.query.isBlank() -> {
            false
        }

        event.key == Key.Escape -> {
            state.clear()
            true
        }

        event.key == Key.DirectionDown -> {
            state.moveSelection(1)
            true
        }

        event.key == Key.DirectionUp -> {
            state.moveSelection(-1)
            true
        }

        event.key == Key.Enter || event.key == Key.NumPadEnter -> {
            state.openSelected()
            true
        }

        else -> {
            false
        }
    }
}
