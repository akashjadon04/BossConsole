package ai.rever.boss.config

import com.teamdev.jxbrowser.engine.RenderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guards the rendering-mode decision in [JxBrowserConfig].
 *
 * Two things are pinned here, and both are load-bearing.
 *
 * The default is HARDWARE_ACCELERATED on every platform, for two different reasons
 * that the KDoc keeps separate: Windows because OFF_SCREEN costs it ~47% on
 * Speedometer 3.1, macOS and Linux because OFF_SCREEN costs them idle power and
 * memory (~10x idle CPU, -1.1 GB RSS in Lite's A/B) even though macOS Speedometer is
 * already ahead of Chrome. It is asserted per platform string anyway — including
 * strings no platform reports — because this value decides how browser content
 * composites against every app overlay, and a platform silently falling through to
 * the wrong branch is not visible in any single test that asserts only the branch it
 * expects.
 *
 * The override precedence: an explicit OFF_SCREEN must win everywhere, because it is
 * now the only way to get the old compositing back if a machine turns out to draw
 * browser content over Compose menus and dialogs.
 */
class JxBrowserRenderingModeTest {
    private val windows = "windows 11"
    private val mac = "mac os x"
    private val linux = "linux"

    @Test
    fun `every platform defaults to HARDWARE_ACCELERATED`() {
        // "darwin" is here on purpose: it contains the substring "win". The default no
        // longer branches on the OS, but this test outlives that — reintroducing a
        // carve-out written as contains("win") would hand macOS the Windows branch.
        for (os in listOf(windows, mac, linux, "darwin", "freebsd", "sunos", "")) {
            for (raw in listOf(null, "", "   ")) {
                assertEquals(
                    RenderingMode.HARDWARE_ACCELERATED,
                    JxBrowserConfig.resolveRenderingMode(raw, os),
                    "expected the default for '$raw' on '$os'",
                )
            }
        }
    }

    @Test
    fun `an explicit OFF_SCREEN overrides the default on every platform`() {
        // The escape hatch: if the HARDWARE overlay handling turns out to be
        // incomplete on some machine, this restores the old behaviour with no rebuild.
        // All three spellings are BossConsoleLite's, so one value works in both repos.
        for (os in listOf(windows, mac, linux)) {
            for (raw in listOf("OFF_SCREEN", "off_screen", "  Off_Screen  ", "OFFSCREEN", "software")) {
                assertEquals(
                    RenderingMode.OFF_SCREEN,
                    JxBrowserConfig.resolveRenderingMode(raw, os),
                    "expected the override to win for '$raw' on '$os'",
                )
            }
        }
    }

    @Test
    fun `an explicit HARDWARE mode is still honoured, not just defaulted into`() {
        // Distinct from the default test: this asserts the spellings are parsed, so a
        // user who pins HARDWARE keeps it if the default is ever narrowed again.
        for (os in listOf(windows, mac, linux)) {
            for (raw in listOf("hardware_accelerated", "HARDWARE", "gpu")) {
                assertEquals(
                    RenderingMode.HARDWARE_ACCELERATED,
                    JxBrowserConfig.resolveRenderingMode(raw, os),
                    "expected the opt-in to work for '$raw' on '$os'",
                )
            }
        }
    }

    @Test
    fun `an unrecognized value falls back to the default, never to a guess`() {
        // A near-miss must not be read as intent — least of all a near-miss of
        // OFF_SCREEN, which would silently change compositing app-wide with no signal
        // beyond a log line. Note "hardware", "gpu", "offscreen" and "software" are NOT
        // here: those are Lite's accepted spellings and are honoured (see above).
        for (raw in listOf("HARDWARE-ACCELERATED", "ACCELERATED", "off screen", "hard ware", "nonsense")) {
            for (os in listOf(windows, mac, linux)) {
                assertEquals(
                    RenderingMode.HARDWARE_ACCELERATED,
                    JxBrowserConfig.resolveRenderingMode(raw, os),
                    "expected the default for unrecognized '$raw' on '$os'",
                )
            }
        }
    }

    @Test
    fun `recognition predicate matches exactly the values resolve honours`() {
        for (raw in listOf("OFF_SCREEN", " hardware_accelerated ", "GPU", "software", "OFFSCREEN")) {
            assertTrue(JxBrowserConfig.isRecognizedRenderingMode(raw), "should be recognized: '$raw'")
        }
        for (raw in listOf(null, "", "   ", "HARDWARE-ACCELERATED", "off screen", "nonsense")) {
            assertFalse(JxBrowserConfig.isRecognizedRenderingMode(raw), "should not be recognized: '$raw'")
        }
    }
}
