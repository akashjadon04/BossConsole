package ai.rever.boss.components.plugin.remote

import ai.rever.boss.keymap.handler.KeymapMatcher
import ai.rever.boss.keymap.model.KeyBinding
import ai.rever.boss.keymap.model.KeymapActions
import ai.rever.boss.keymap.model.KeymapSettings
import ai.rever.boss.keymap.model.ShortcutContext
import ai.rever.boss.keymap.presets.KeymapPresets
import ai.rever.boss.ui.sdk.WidgetEvent
import ai.rever.boss.utils.SystemUtils
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import androidx.compose.ui.input.key.KeyEvent as ComposeKeyEvent
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * The key-routing policy: **the host keymap wins, and the plugin gets what is left.**
 *
 * #34 deferred `key` emission for want of exactly this decision. The risk it was guarding against is
 * specific — an out-of-process plugin that can swallow `Cmd+W` traps the user in a panel it also
 * controls — so these tests are about what is *refused* at least as much as what is delivered.
 *
 * Three separate things make a host shortcut safe, and this file asserts the two that are decidable
 * without AWT:
 *
 * 1. `AWTKeyboardInterceptor` consumes claimed keys at the `KeyboardFocusManager`, upstream of Compose
 *    entirely — structural, and not something a unit test can meaningfully re-check.
 * 2. The forwarding rule refuses anything the live keymap binds, so the guarantee does not rest on
 *    dispatch order alone. **Here.**
 * 3. The tap never consumes — asserted in [RemoteSurfaceInputComposeTest], against a real composition,
 *    since "the key carried on past the surface" is a statement about a modifier chain.
 *
 * Events are built with Compose's own `KeyEvent(...)` factory, which is `@InternalComposeUiApi`. The
 * alternative — driving keys through a composition — is what [RemoteSurfaceInputComposeTest] does; what
 * is wanted *here* is to sweep the whole shipped preset in one cheap pass, which needs synthetic events.
 * If a Compose upgrade removes the factory this fails to compile, which is the right failure.
 */
@OptIn(InternalComposeUiApi::class)
class RemoteSurfaceKeyRoutingTest {
    @Test
    fun `a real host shortcut from the shipped preset is claimed by the host and never forwarded`() {
        // Taken from the preset rather than hardcoded, so the test still means "a real shortcut" after
        // someone rebinds Cmd+T. Both halves are asserted: the host still matches it (the shortcut
        // fires), and the surface refuses it (it is not *also* handed to the plugin).
        val preset = KeymapPresets.getBOSSDefault()
        val newTab = preset.shortcuts[KeymapActions.TAB_NEW]
        val binding = assertNotNull(newTab, "the shipped preset must bind ${KeymapActions.TAB_NEW}")
        val event = binding.asKeyDown()

        assertNotNull(
            KeymapMatcher(preset).match(event, ShortcutContext.GLOBAL),
            "the host keymap must still claim ${binding.displayString()} - otherwise this asserts nothing",
        )
        assertNull(
            event.toForwardedKey(KeymapMatcher(preset)),
            "a key the host keymap claims must not also reach the plugin",
        )
    }

    @Test
    fun `every single-key global binding in the shipped preset is refused`() {
        // One binding proves the rule is wired; the whole preset proves there is no shortcut-shaped hole
        // in it. Cheap, and it is the assertion that catches a matcher regression rather than a wiring
        // one. Restricted to single-character keys because that is the set [asKeyDown] can synthesise
        // faithfully — "Tab" and "Left" have no char to derive a VK_ code from — and to bindings with a
        // system modifier, which are the ones the host actually dispatches; the rest are covered by the
        // Shift+/ test above, which asserts the opposite.
        val preset = KeymapPresets.getBOSSDefault()
        val forwarded =
            preset.shortcuts.values
                .filter { it.enabled && it.context == ShortcutContext.GLOBAL && it.key.length == 1 }
                .filter { KeymapMatcher(preset).hasSystemModifier(it) }
                .filter { it.asKeyDown().toForwardedKey(KeymapMatcher(preset)) != null }
                .map { it.actionId }

        assertEquals(emptyList<String>(), forwarded, "no host shortcut may be forwarded to a plugin")
    }

    @Test
    fun `a chord the host does not bind is forwarded with its key code and modifiers intact`() {
        // The payload is the point: #48 replaced a (type, data) string pair precisely because it could
        // not carry a key's modifiers, so "it arrived" is not enough — it has to arrive whole.
        val keymap = onlyBinding(key = "T")

        val forwarded =
            keyDown(AwtKeyEvent.VK_K, meta = true, shift = true, alt = true, ctrl = true)
                .toForwardedKey(keymap)

        assertEquals(
            WidgetEvent.Key(keyCode = AwtKeyEvent.VK_K, ctrl = true, alt = true, shift = true, meta = true),
            forwarded,
        )
    }

    @Test
    fun `an unmodified key press is forwarded`() {
        // The AWT interceptor never even looks at these — it returns early unless Meta, Ctrl or Alt is
        // down — so a plain Escape or F5 can only ever reach a plugin through this path. Worth pinning:
        // a stricter "modifiers required" rule would silently make the whole family useless for the keys
        // plugins actually want.
        val forwarded = keyDown(AwtKeyEvent.VK_ESCAPE).toForwardedKey(onlyBinding(key = "T"))

        assertEquals(WidgetEvent.Key(keyCode = AwtKeyEvent.VK_ESCAPE), forwarded)
    }

    @Test
    fun `a key release is not forwarded`() {
        // ui_protocol's KeyEvent has no up/down discriminator, so forwarding both edges delivers every
        // press twice with nothing to tell them apart.
        val release = keyEvent(AwtKeyEvent.VK_ESCAPE, KeyEventType.KeyUp)

        assertNull(release.toForwardedKey(onlyBinding(key = "T")))
    }

    @Test
    fun `a bound key the host would never dispatch is still forwarded`() {
        // The bug review caught. AWTKeyboardInterceptor returns before it consults the keymap unless
        // Meta, Ctrl or Alt is down, so a binding with no system modifier is never dispatched — and
        // refusing it here too made the key vanish, claimed by neither side. The shipped preset really
        // has one: Shift+/ opens the shortcut sheet, so `?` on a focused remote surface did nothing.
        val preset = KeymapPresets.getBOSSDefault()
        val help = preset.shortcuts[KeymapActions.HELP_SHORTCUTS]
        val binding = assertNotNull(help, "the preset must bind ${KeymapActions.HELP_SHORTCUTS}")
        assertEquals(listOf("Shift"), binding.modifiers, "this test is about the modifier-less case")
        val matcher = KeymapMatcher(preset)
        val event = keyDown(AwtKeyEvent.VK_SLASH, shift = true)

        assertNotNull(matcher.match(event, ShortcutContext.GLOBAL), "the keymap does match it")
        assertNotNull(
            event.toForwardedKey(matcher),
            "…but the host never dispatches it, so declining to forward it would lose the key entirely",
        )
    }

    @Test
    fun `a modifier pressed on its own is not forwarded`() {
        // Found by the Compose test, not by reading: holding Shift to type a capital letter delivered a
        // bare VK_SHIFT ahead of the letter, so a plugin saw two events for one keystroke. The modifier
        // is not lost — it rides on the key it qualifies, which is the only shape the wire type has.
        val keymap = onlyBinding(key = "T")
        val modifiers = listOf(AwtKeyEvent.VK_SHIFT, AwtKeyEvent.VK_CONTROL, AwtKeyEvent.VK_ALT, AwtKeyEvent.VK_META)

        val forwarded = modifiers.filter { keyDown(it).toForwardedKey(keymap) != null }

        assertEquals(emptyList<Int>(), forwarded, "a modifier alone is not a key press a plugin can use")
    }

    @Test
    fun `a binding the user disabled is forwarded again`() {
        // Disabling a shortcut hands the key back to the app. The plugin is part of the app, so it should
        // get it — and this is the case that fails if the check asks "is this chord in the keymap" rather
        // than "would the keymap act on it".
        val binding = KeyBinding(actionId = "test.action", key = "T", modifiers = listOf("Cmd"))
        val enabled = KeymapMatcher(KeymapSettings(shortcuts = mapOf(binding.actionId to binding)))
        val off = binding.copy(enabled = false)
        val disabled = KeymapMatcher(KeymapSettings(shortcuts = mapOf(off.actionId to off)))
        val event = keyDown(AwtKeyEvent.VK_T, primary = true)

        assertNull(event.toForwardedKey(enabled), "enabled, the host claims it")
        assertNotNull(event.toForwardedKey(disabled), "disabled, the host does not act on it - the plugin may have it")
    }

    /**
     * A matcher over exactly one GLOBAL binding on [key] plus the platform's primary modifier.
     *
     * Built rather than loaded so the assertions do not depend on whatever `~/.boss/keymap-settings.json`
     * the suite happens to run beside — `KeymapSettingsManager` reads the real user file on class init.
     */
    private fun onlyBinding(key: String): KeymapMatcher {
        val binding =
            KeyBinding(
                actionId = "test.action",
                key = key,
                modifiers = listOf("Cmd"),
                context = ShortcutContext.GLOBAL,
            )
        return KeymapMatcher(KeymapSettings(shortcuts = mapOf(binding.actionId to binding)))
    }

    /**
     * The key-down event a user produces by typing this binding.
     *
     * Only the primary keystroke and only the modifiers `KeymapMatcher` distinguishes. A binding whose
     * key name has no single char to derive a `VK_` code from would synthesise an event matching nothing
     * — which would *fail* the preset sweep rather than pass it, hence the `key.length == 1` filter
     * there rather than a tolerance here.
     */
    private fun KeyBinding.asKeyDown(): ComposeKeyEvent {
        val mods = modifiers.map { it.lowercase() }
        return keyDown(
            keyCode = AwtKeyEvent.getExtendedKeyCodeForChar(key.first().code),
            primary = mods.any { it == "cmd" || it == "meta" },
            ctrl = mods.any { it == "ctrl" || it == "control" },
            shift = mods.any { it == "shift" },
            alt = mods.any { it == "alt" || it == "option" },
        )
    }

    /**
     * A key-down event.
     *
     * @param primary the platform's "Cmd" modifier. `KeymapMatcher` is platform-aware — a binding on
     *   `Cmd` matches Meta on macOS and Ctrl everywhere else — so a test that hardcoded one of them
     *   would assert the opposite thing on the other two CI runners.
     */
    private fun keyDown(
        keyCode: Int,
        primary: Boolean = false,
        meta: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ): ComposeKeyEvent =
        keyEvent(
            keyCode = keyCode,
            type = KeyEventType.KeyDown,
            meta = meta || (primary && SystemUtils.isMacOS),
            ctrl = ctrl || (primary && !SystemUtils.isMacOS),
            shift = shift,
            alt = alt,
        )

    private fun keyEvent(
        keyCode: Int,
        type: KeyEventType,
        meta: Boolean = false,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ): ComposeKeyEvent =
        ComposeKeyEvent(
            key = Key(nativeKeyCode = keyCode),
            type = type,
            isMetaPressed = meta,
            isCtrlPressed = ctrl,
            isShiftPressed = shift,
            isAltPressed = alt,
        )
}
