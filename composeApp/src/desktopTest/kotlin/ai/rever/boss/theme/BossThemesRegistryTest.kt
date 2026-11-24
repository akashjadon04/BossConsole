package ai.rever.boss.theme

import ai.rever.boss.plugin.ui.BossAppTheme
import ai.rever.boss.plugin.ui.BossBlueprintColorScheme
import ai.rever.boss.plugin.ui.BossBlueprintLightColorScheme
import ai.rever.boss.plugin.ui.BossColorScheme
import ai.rever.boss.plugin.ui.BossNvidiaColorScheme
import ai.rever.boss.plugin.ui.BossThemeController
import ai.rever.boss.plugin.ui.BossThemes
import androidx.compose.ui.graphics.Color
import org.junit.jupiter.api.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Invariants of the theme registry itself, and the contrast floors each palette
 * is allowed to sit at.
 *
 * The default-id tests exist because [BossThemes.DEFAULT_ID],
 * [BossThemes.byId]'s fallback and [BossThemeController]'s initial value are
 * three separate expressions of "the default theme". A mismatch is invisible at
 * runtime until a user with an unknown persisted id gets a different theme than
 * a fresh install.
 */
class BossThemesRegistryTest {
    @Test
    fun `default id resolves to a registered theme`() {
        val ids = BossThemes.all.map { it.id }
        assertTrue(
            BossThemes.DEFAULT_ID in ids,
            "DEFAULT_ID '${BossThemes.DEFAULT_ID}' is not in BossThemes.all ($ids)",
        )
    }

    @Test
    fun `byId falls back to the default theme for null and unknown ids`() {
        val default = BossThemes.all.first { it.id == BossThemes.DEFAULT_ID }
        assertEquals(default, BossThemes.byId(null), "byId(null) must resolve to DEFAULT_ID's theme")
        assertEquals(default, BossThemes.byId("no-such-theme"), "byId(unknown) must resolve to DEFAULT_ID's theme")
    }

    @Test
    fun `theme ids and names are unique`() {
        val ids = BossThemes.all.map { it.id }
        assertEquals(ids.size, ids.distinct().size, "duplicate theme ids: $ids")
        val names = BossThemes.all.map { it.name }
        assertEquals(names.size, names.distinct().size, "duplicate theme names: $names")
    }

    @Test
    fun `blueprint is the default and leads the picker`() {
        assertEquals("blueprint", BossThemes.DEFAULT_ID)
        assertEquals("blueprint", BossThemes.all.first().id, "Blueprint should be the first card in the picker")
    }

    @Test
    fun `blueprint carries the bossconsole_ai palette anchors`() {
        // The site's own custom properties. If these drift, the app no longer
        // matches the download page it ships from — the point of the theme.
        assertEquals(Color(0xFF0F5BFF), BossBlueprintColorScheme.signal, "--blue")
        assertEquals(Color(0xFF0F5BFF), BossBlueprintLightColorScheme.signal, "--blue")
        assertEquals(Color(0xFF05070B), BossBlueprintColorScheme.ink, "--ink")
        assertEquals(Color(0xFFF5F7FB), BossBlueprintLightColorScheme.ink, "--paper")
        assertEquals(Color(0xFFDCE7FF), BossBlueprintLightColorScheme.signalWash, "--blue-soft")
    }

    @Test
    fun `blueprint lightness flags match their floors`() {
        val dark = BossThemes.all.first { it.id == "blueprint" }
        val light = BossThemes.all.first { it.id == "blueprint-light" }
        assertFalse(dark.isLight, "blueprint is a dark theme")
        assertTrue(light.isLight, "blueprint-light is a light theme")
        // isLight drives Chromium's prefers-color-scheme (FluckEngine) and the
        // Material factory, so a wrong flag is a functional bug, not cosmetics.
        assertTrue(luminance(dark.colors.ink) < 0.5, "blueprint's floor should be dark")
        assertTrue(luminance(light.colors.ink) > 0.5, "blueprint-light's floor should be light")
    }

    @Test
    fun `nvidia carries the brand anchors`() {
        // #76B900 is the whole point of the theme; if it drifts it is no longer
        // an NVIDIA theme, just a green one. Black is the site's actual floor,
        // and black-on-green is how the site draws its buttons.
        assertEquals(Color(0xFF76B900), BossNvidiaColorScheme.signal, "NVIDIA green")
        assertEquals(Color(0xFF000000), BossNvidiaColorScheme.ink, "nvidia.com is pure black")
        assertEquals(Color(0xFF000000), BossNvidiaColorScheme.onSignal, "green button, black type")
    }

    /**
     * The reason `nvidia` sets `signalText = signal` rather than inventing a
     * twin. If someone darkens the brand green for a light variant and copies
     * the value here, this fails before the aliasing test does and says why.
     */
    @Test
    fun `nvidia green is legible as both a glyph and a fill`() {
        val c = BossNvidiaColorScheme
        assertEquals(c.signal, c.signalText, "NVIDIA green clears the text floor unaided")
        surfaces(c).forEach { (name, bg) ->
            assertTrue(
                contrast(c.signal, bg) >= 4.5,
                "nvidia: signal is only ${format(contrast(c.signal, bg))}:1 on $name",
            )
        }
        assertTrue(
            contrast(c.onSignal, c.signal) >= 4.5,
            "nvidia: onSignal is only ${format(contrast(c.onSignal, c.signal))}:1 on the green fill",
        )
    }

    @Test
    fun `nvidia keeps ok clear of the brand green`() {
        // With a green `signal`, an `ok` in the same hue makes a success chip
        // read as a primary action. Distance is the invariant, not the hex.
        val c = BossNvidiaColorScheme
        assertTrue(
            hueDistance(c.ok, c.signal) >= 40.0,
            "nvidia: ok is only ${format(hueDistance(c.ok, c.signal))} deg from signal",
        )
    }

    /**
     * Text tokens against every surface they are actually drawn on.
     *
     * Checked against `panel` and `raised` too, not just `ink`: in the dark
     * themes both are *lighter* than `ink`, so every ratio is worse in practice
     * than an ink-only check reports, and chrome paints on them far more often
     * than on the bare floor.
     */
    @Test
    fun `every theme clears the text contrast floors on every surface`() {
        BossThemes.all.forEach { theme ->
            val c = theme.colors
            surfaces(c).forEach { (name, bg) ->
                assertFloor(theme, "textPrimary on $name", c.textPrimary, bg, 7.0)
                assertFloor(theme, "textSecondary on $name", c.textSecondary, bg, 4.0)
                // The whole reason `signalText` exists: signal-colored glyphs are
                // held to the real text floor on every surface, while `signal`
                // itself is only held to the 3:1 component floor below.
                assertFloor(theme, "signalText on $name", c.signalText, bg, 4.5)
            }
            assertFloor(theme, "onSignal on signal", c.onSignal, c.signal, 4.5)
            assertFloor(theme, "onData on data", c.onData, c.data, 4.5)
        }
    }

    /**
     * Non-text tokens against the 3:1 WCAG floor for UI components, on every
     * surface they are drawn on.
     *
     * `signal` is held to 3.0 and not 4.5 on purpose: Blueprint's `--blue` sits
     * at ~3.8:1 on ink, exactly as it does on bossconsole.ai, where emphasis
     * comes from a `signalWash` fill plus a 2.dp indicator rather than from a
     * hairline of `signal` alone. Do not brighten `signal` to buy headroom —
     * thicken the indicator, lift the wash, or (for a glyph) use `signalText`.
     */
    @Test
    fun `every theme clears the UI-component contrast floors on every surface`() {
        BossThemes.all.forEach { theme ->
            val c = theme.colors
            surfaces(c).forEach { (name, bg) ->
                assertFloor(theme, "data on $name", c.data, bg, 3.0)
                assertFloor(theme, "alert on $name", c.alert, bg, 3.0)
                assertFloor(theme, "ok on $name", c.ok, bg, 3.0)
                assertFloor(theme, "warn on $name", c.warn, bg, 3.0)
                if (theme.id !in SIGNAL_CONTRAST_DEBT) {
                    assertFloor(theme, "signal on $name", c.signal, bg, 3.0)
                }
            }
        }
    }

    /**
     * `signalText` may only equal `signal` in a theme where `signal` already
     * clears the text floor — otherwise the token is decorative and the sweep
     * that moved glyphs onto it bought nothing.
     */
    @Test
    fun `signalText only aliases signal where signal is already legible text`() {
        BossThemes.all.forEach { theme ->
            val c = theme.colors
            if (c.signalText == c.signal) {
                surfaces(c).forEach { (name, bg) ->
                    assertTrue(
                        contrast(c.signal, bg) >= 4.5,
                        "${theme.id}: signalText aliases signal, but signal is only " +
                            "${format(contrast(c.signal, bg))}:1 on $name — give it its own value",
                    )
                }
            }
        }
    }

    // Return type inferred so this fits on one line: ktlint requires the body
    // expression on the signature line, detekt caps that line at 120 chars, and
    // spelling out List<Pair<String, Color>> cannot satisfy both.
    private fun surfaces(c: BossColorScheme) = listOf("ink" to c.ink, "panel" to c.panel, "raised" to c.raised)

    /**
     * Pins the one pre-existing violation of the floor above so it cannot spread.
     *
     * Daylight's amber `signal` (#D9871A) is 2.63:1 on its near-white floor —
     * amber on white is a hard combination and this predates the Blueprint work.
     * It is recorded here rather than silently exempted so that (a) a new theme
     * cannot quietly join the list, and (b) whoever darkens Daylight's signal is
     * told to delete the entry.
     */
    @Test
    fun `no theme joins or silently leaves the signal contrast debt list`() {
        val failing =
            BossThemes.all
                .filter { contrast(it.colors.signal, it.colors.ink) < 3.0 }
                .map { it.id }
                .toSet()
        assertEquals(
            SIGNAL_CONTRAST_DEBT,
            failing,
            "signal-vs-ink contrast debt changed. If a theme was fixed, remove it from " +
                "SIGNAL_CONTRAST_DEBT; if a new theme is failing, darken its signal instead.",
        )
    }

    @Test
    fun `signalWash reads as a fill without swallowing the text on it`() {
        BossThemes.all.forEach { theme ->
            val c = theme.colors
            val vsPanel = contrast(c.signalWash, c.panel)
            assertTrue(
                vsPanel >= 1.05,
                "${theme.id}: signalWash is indistinguishable from panel (${format(vsPanel)}:1)",
            )
            assertFloor(theme, "textPrimary on signalWash", c.textPrimary, c.signalWash, 4.5)
        }
    }

    private fun assertFloor(
        theme: BossAppTheme,
        what: String,
        fg: Color,
        bg: Color,
        floor: Double,
    ) {
        val ratio = contrast(fg, bg)
        assertTrue(
            ratio >= floor,
            "${theme.id}: $what is ${format(ratio)}:1, below the $floor:1 floor",
        )
    }

    private fun format(ratio: Double): String = ((ratio * 100).toInt() / 100.0).toString()

    /** Shortest angular distance between two hues, in degrees. */
    private fun hueDistance(
        a: Color,
        b: Color,
    ): Double {
        val delta = kotlin.math.abs(hue(a) - hue(b))
        return min(delta, 360.0 - delta)
    }

    private fun hue(c: Color): Double {
        val r = c.red.toDouble()
        val g = c.green.toDouble()
        val b = c.blue.toDouble()
        val hi = maxOf(r, g, b)
        val lo = minOf(r, g, b)
        val span = hi - lo
        if (span == 0.0) return 0.0
        val h =
            when (hi) {
                r -> 60 * (((g - b) / span) % 6)
                g -> 60 * (((b - r) / span) + 2)
                else -> 60 * (((r - g) / span) + 4)
            }
        return if (h < 0) h + 360 else h
    }

    /** WCAG 2.x relative luminance. */
    private fun luminance(c: Color): Double {
        fun channel(v: Float): Double {
            val s = v.toDouble()
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    }

    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private companion object {
        /** Themes whose `signal` predates the 3:1 floor. See the test that pins this. */
        val SIGNAL_CONTRAST_DEBT = setOf("daylight")
    }
}
