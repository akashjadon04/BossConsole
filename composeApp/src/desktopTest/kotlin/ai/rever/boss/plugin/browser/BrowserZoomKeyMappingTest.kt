package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.ui.KeyCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for [FluckEngine.resolveBrowserZoomAction] — no JxBrowser browser required.
 *
 * These exist because the mapping's failure mode is silent. JxBrowser 9.4.0 has no
 * `KEY_CODE_EQUALS` and no `KEY_CODE_MINUS`, so an implementation reaching for those intuitive
 * names does not fail to compile — it compiles against whatever constant it does find and then
 * never matches the key the user actually pressed. That is exactly how zoom came to do nothing on
 * Windows, where the native interceptor is the only layer that sees the chord at all.
 */
class BrowserZoomKeyMappingTest {
    @Test
    fun `main-row and numpad plus zoom in`() {
        assertEquals(
            FluckEngine.BrowserZoomAction.IN,
            FluckEngine.resolveBrowserZoomAction(KeyCode.KEY_CODE_OEM_PLUS, shiftDown = false),
        )
        assertEquals(
            FluckEngine.BrowserZoomAction.IN,
            FluckEngine.resolveBrowserZoomAction(KeyCode.KEY_CODE_ADD, shiftDown = false),
        )
    }

    @Test
    fun `main-row and numpad minus zoom out`() {
        assertEquals(
            FluckEngine.BrowserZoomAction.OUT,
            FluckEngine.resolveBrowserZoomAction(KeyCode.KEY_CODE_OEM_MINUS, shiftDown = false),
        )
        assertEquals(
            FluckEngine.BrowserZoomAction.OUT,
            FluckEngine.resolveBrowserZoomAction(KeyCode.KEY_CODE_SUBTRACT, shiftDown = false),
        )
    }

    @Test
    fun `main-row and numpad zero reset zoom`() {
        assertEquals(
            FluckEngine.BrowserZoomAction.RESET,
            FluckEngine.resolveBrowserZoomAction(KeyCode.KEY_CODE_0, shiftDown = false),
        )
        assertEquals(
            FluckEngine.BrowserZoomAction.RESET,
            FluckEngine.resolveBrowserZoomAction(KeyCode.KEY_CODE_NUMPAD0, shiftDown = false),
        )
    }

    @Test
    fun `shift claims only zoom in, the Ctrl+Shift+= spelling of Ctrl+plus`() {
        assertEquals(
            FluckEngine.BrowserZoomAction.IN,
            FluckEngine.resolveBrowserZoomAction(KeyCode.KEY_CODE_OEM_PLUS, shiftDown = true),
        )
        // Shift is meaningless for these two, so they decline it rather than swallowing a chord
        // some page or other shortcut may want.
        assertNull(FluckEngine.resolveBrowserZoomAction(KeyCode.KEY_CODE_OEM_MINUS, shiftDown = true))
        assertNull(FluckEngine.resolveBrowserZoomAction(KeyCode.KEY_CODE_0, shiftDown = true))
    }

    @Test
    fun `keys owned by the other browser shortcuts are not zoom chords`() {
        // The regression this pins: every one of these shares the interceptor's main-modifier
        // branch with zoom, so a mapping written against the wrong constant could quietly claim
        // one of them - or, more likely, claim nothing and leave zoom dead the way it was.
        listOf(
            KeyCode.KEY_CODE_R,
            KeyCode.KEY_CODE_N,
            KeyCode.KEY_CODE_T,
            KeyCode.KEY_CODE_W,
            KeyCode.KEY_CODE_G,
            KeyCode.KEY_CODE_F,
            KeyCode.KEY_CODE_1,
        ).forEach { keyCode ->
            assertNull(
                FluckEngine.resolveBrowserZoomAction(keyCode, shiftDown = false),
                "$keyCode must not be treated as a zoom chord",
            )
            assertNull(
                FluckEngine.resolveBrowserZoomAction(keyCode, shiftDown = true),
                "Shift+$keyCode must not be treated as a zoom chord",
            )
        }
    }
}
