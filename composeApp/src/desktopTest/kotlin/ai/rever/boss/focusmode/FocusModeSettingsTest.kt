package ai.rever.boss.focusmode

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for FocusModeSettings data class.
 *
 * Tests cover:
 * - Default values for new users
 * - Property validation
 */
class FocusModeSettingsTest {
    // ==================== DEFAULT VALUES TESTS ====================

    @Test
    fun `default settings should have focus mode disabled`() {
        val settings = FocusModeSettings()
        assertFalse(settings.enabled, "Focus mode should be disabled by default for new users")
    }

    @Test
    fun `default settings should have auto-reveal enabled`() {
        val settings = FocusModeSettings()
        assertTrue(settings.autoRevealEnabled, "Auto-reveal should be enabled by default")
    }

    @Test
    fun `default settings should have 30px reveal offset`() {
        val settings = FocusModeSettings()
        assertEquals(30f, settings.revealOffsetPx, "Default reveal offset should be 30px")
    }

    // ==================== CUSTOM VALUES TESTS ====================

    @Test
    fun `settings can be created with focus mode enabled`() {
        val settings = FocusModeSettings(enabled = true)
        assertTrue(settings.enabled, "Focus mode should be enabled when explicitly set")
    }

    @Test
    fun `settings can be created with auto-reveal disabled`() {
        val settings = FocusModeSettings(autoRevealEnabled = false)
        assertFalse(settings.autoRevealEnabled, "Auto-reveal should be disabled when explicitly set")
    }

    @Test
    fun `settings can be created with custom reveal offset`() {
        val settings = FocusModeSettings(revealOffsetPx = 25f)
        assertEquals(25f, settings.revealOffsetPx, "Reveal offset should match custom value")
    }

    // ==================== COPY TESTS ====================

    @Test
    fun `copy preserves unchanged values`() {
        val original = FocusModeSettings(enabled = true, autoRevealEnabled = false, revealOffsetPx = 20f)
        val copied = original.copy(enabled = false)

        assertFalse(copied.enabled, "Enabled should be updated")
        assertFalse(copied.autoRevealEnabled, "Auto-reveal should be preserved")
        assertEquals(20f, copied.revealOffsetPx, "Reveal offset should be preserved")
    }

    @Test
    fun `toggling focus mode preserves other settings`() {
        val original = FocusModeSettings(enabled = false, autoRevealEnabled = true, revealOffsetPx = 15f)
        val toggled = original.copy(enabled = !original.enabled)

        assertTrue(toggled.enabled, "Focus mode should be toggled on")
        assertTrue(toggled.autoRevealEnabled, "Auto-reveal should be preserved")
        assertEquals(15f, toggled.revealOffsetPx, "Reveal offset should be preserved")
    }

    // region platform defaults

    /**
     * Hover-to-reveal starts OFF on Windows because the mechanism cannot work there. Reveal is
     * driven by Compose `onPointerEvent(Enter/Exit)` on edge strips, and Windows runs the browser
     * in HARDWARE mode, where Chromium owns a foreign native window that composites over the
     * Compose scene. The OS routes pointer events to that window, so Compose never sees the
     * pointer reach an edge strip beneath the browser: a user in focus mode with a browser tab
     * open would sweep the edge and the bars would simply never come back.
     */
    @Test
    fun `windows starts with hover-to-reveal off`() {
        for (os in listOf("Windows 10", "Windows 11", "Windows Server 2022", "windows")) {
            assertFalse(FocusModeSettings.defaultAutoReveal(os), os)
            assertFalse(FocusModeSettings.defaultsFor(os).autoRevealEnabled, os)
        }
    }

    @Test
    fun `every other platform keeps hover-to-reveal on`() {
        for (os in listOf("Mac OS X", "macOS", "Linux", "FreeBSD", "SunOS", "")) {
            assertTrue(FocusModeSettings.defaultAutoReveal(os), os)
            assertTrue(FocusModeSettings.defaultsFor(os).autoRevealEnabled, os)
        }
    }

    /**
     * `"darwin"` contains `"win"`. A check written with `contains` would disable a feature on
     * macOS that works perfectly well there. The same trap is pinned in `ResourceModeTest` and
     * `JxBrowserRenderingModeTest`.
     */
    @Test
    fun `darwin is not windows`() {
        assertTrue(FocusModeSettings.defaultAutoReveal("darwin"))
    }

    /**
     * Reveal and the two sidebars are the only platform-specific defaults; focus mode itself
     * stays off everywhere, and the top and bottom bars are still cleared on Windows, so focus
     * mode does something there.
     */
    @Test
    fun `the platform default changes nothing else`() {
        val win = FocusModeSettings.defaultsFor("Windows 11")
        val mac = FocusModeSettings.defaultsFor("Mac OS X")
        assertFalse(win.enabled)
        assertFalse(mac.enabled)
        assertEquals(mac.revealOffsetPx, win.revealOffsetPx)
        assertEquals(mac.revealDelayMs, win.revealDelayMs)
        assertEquals(mac.hideTopBar, win.hideTopBar)
        assertEquals(mac.hideBottomBar, win.hideBottomBar)
    }

    /**
     * Windows keeps both sidebars in focus mode for the same reason hover-to-reveal starts off
     * there: with the reveal unable to fire, hiding a sidebar is a one-way door.
     */
    @Test
    fun `windows keeps both sidebars visible in focus mode`() {
        for (os in listOf("Windows 10", "Windows 11", "Windows Server 2022", "windows")) {
            assertFalse(FocusModeSettings.defaultHidesSidebars(os), os)
            val defaults = FocusModeSettings.defaultsFor(os)
            assertFalse(defaults.hideLeftSidebar, os)
            assertFalse(defaults.hideRightSidebar, os)
            // Focus mode still trims the horizontal chrome, so it is not a no-op there.
            assertTrue(defaults.hideTopBar, os)
            assertTrue(defaults.hideBottomBar, os)
            assertTrue(defaults.copy(enabled = true).hidesAnything(), os)
        }
    }

    @Test
    fun `every other platform clears all four edges`() {
        for (os in listOf("Mac OS X", "macOS", "Linux", "FreeBSD", "SunOS", "darwin", "")) {
            val defaults = FocusModeSettings.defaultsFor(os).copy(enabled = true)
            for (edge in FocusModeEdge.entries) {
                assertTrue(defaults.hides(edge), "$os / $edge")
            }
        }
    }

    // endregion

    // region per-edge switches

    /** Every edge stays visible while focus mode is off, whatever the switches say. */
    @Test
    fun `focus mode off hides nothing`() {
        val allOn = FocusModeSettings(enabled = false)
        for (edge in FocusModeEdge.entries) {
            assertFalse(allOn.hides(edge), edge.name)
        }
        assertFalse(allOn.hidesAnything())
    }

    @Test
    fun `each switch controls exactly its own edge`() {
        val settings =
            FocusModeSettings(
                enabled = true,
                hideTopBar = true,
                hideLeftSidebar = false,
                hideRightSidebar = false,
                hideBottomBar = true,
            )

        assertTrue(settings.hides(FocusModeEdge.TOP))
        assertFalse(settings.hides(FocusModeEdge.LEFT))
        assertFalse(settings.hides(FocusModeEdge.RIGHT))
        assertTrue(settings.hides(FocusModeEdge.BOTTOM))
        assertTrue(settings.hidesAnything())
    }

    @Test
    fun `focus mode with every edge off hides nothing`() {
        val settings =
            FocusModeSettings(
                enabled = true,
                hideTopBar = false,
                hideLeftSidebar = false,
                hideRightSidebar = false,
                hideBottomBar = false,
            )
        assertFalse(settings.hidesAnything())
    }

    // endregion

    // region decoding stored files

    /**
     * The case this merge exists for: a Windows install that already had a settings file, written
     * before the per-edge switches existed. A plain decode fills the missing keys from the class
     * defaults, which hide both sidebars - on the one platform that cannot reveal them again.
     */
    @Test
    fun `a settings file predating the switches takes the platform default`() {
        val legacy = """{"enabled":true,"autoRevealEnabled":false,"revealOffsetPx":30.0,"revealDelayMs":500}"""

        val decoded = FocusModeSettings.decodeWithDefaults(legacy, FocusModeSettings.defaultsFor("Windows 11"))

        assertFalse(decoded.hideLeftSidebar, "an absent key must not hide a sidebar on Windows")
        assertFalse(decoded.hideRightSidebar)
        assertTrue(decoded.hideTopBar)
        assertTrue(decoded.hideBottomBar)
        // The keys the file did carry are still the file's own.
        assertTrue(decoded.enabled)
        assertFalse(decoded.autoRevealEnabled)
        assertEquals(500L, decoded.revealDelayMs)
    }

    /** A deliberate choice always wins over the platform default, in both directions. */
    @Test
    fun `stored keys win over the defaults`() {
        val stored = """{"hideLeftSidebar":true,"hideRightSidebar":true,"hideTopBar":false}"""

        val decoded = FocusModeSettings.decodeWithDefaults(stored, FocusModeSettings.defaultsFor("Windows 11"))

        assertTrue(decoded.hideLeftSidebar, "a Windows user who asked for the full sweep keeps it")
        assertTrue(decoded.hideRightSidebar)
        assertFalse(decoded.hideTopBar)
    }

    @Test
    fun `an unknown key from a newer build is ignored`() {
        val fromTheFuture = """{"enabled":true,"hideDiagonalBar":true}"""

        val decoded = FocusModeSettings.decodeWithDefaults(fromTheFuture, FocusModeSettings.defaultsFor("Linux"))

        assertTrue(decoded.enabled)
        assertTrue(decoded.hideLeftSidebar)
    }

    /**
     * An empty object resolves the NEW keys from the platform defaults and everything else
     * from the class defaults. It is not the same as a missing file: a missing file means
     * nobody has ever chosen anything (the manager writes the platform defaults straight
     * out), while `{}` was written by a build that omitted every class-default value.
     */
    @Test
    fun `an empty file resolves only the new keys from the platform defaults`() {
        val defaults = FocusModeSettings.defaultsFor("Windows 11")

        val decoded = FocusModeSettings.decodeWithDefaults("{}", defaults)

        assertFalse(decoded.hideLeftSidebar, "a new key resolves from the platform default")
        assertFalse(decoded.hideRightSidebar)
        assertTrue(decoded.autoRevealEnabled, "an old key keeps the class default, not the platform one")
    }

    /**
     * The regression this scoping exists to prevent. Before this build the writer used
     * `encodeDefaults = false`, so a Windows user who deliberately switched hover-to-reveal
     * ON produced a file with `autoRevealEnabled` absent - it equals the class default. If
     * absence were resolved from the platform defaults, their choice would silently flip off
     * on upgrade, on the platform whose default is the opposite.
     */
    @Test
    fun `a windows user who turned hover-reveal on keeps it across the upgrade`() {
        // What the old encodeDefaults = false writer produced for that user.
        val oldFile = """{"enabled":true}"""

        val decoded = FocusModeSettings.decodeWithDefaults(oldFile, FocusModeSettings.defaultsFor("Windows 11"))

        assertTrue(decoded.autoRevealEnabled, "a pre-existing preference must survive the upgrade")
        assertTrue(decoded.enabled)
        // The new keys still take the Windows defaults, which is the point of the merge.
        assertFalse(decoded.hideLeftSidebar)
        assertFalse(decoded.hideRightSidebar)
    }

    /**
     * The round trip through the manager's own encoder, which is where merging over
     * *platform* defaults turns a missing `encodeDefaults` into silent data loss.
     *
     * Without it, a value equal to the CLASS default is omitted from the file, and the
     * merge then reads that absence as "never chosen" and substitutes the platform
     * default. On Windows the two disagree for exactly the fields this feature adds, so a
     * user switching a sidebar back on would write nothing and find it off next launch -
     * on the one platform where the escape hatch is the whole point.
     */
    @Test
    fun `a chosen value survives a save and load on windows`() {
        val platform = FocusModeSettings.defaultsFor("Windows 11")
        // Every one of these equals the class default and differs from the Windows default.
        val chosen = platform.copy(hideLeftSidebar = true, hideRightSidebar = true, autoRevealEnabled = true)

        val written = managerJson.encodeToString(FocusModeSettings.serializer(), chosen)
        val reloaded = FocusModeSettings.decodeWithDefaults(written, platform)

        assertEquals(chosen, reloaded, "a deliberate choice must not revert on restart: $written")
        assertTrue(reloaded.hideLeftSidebar)
        assertTrue(reloaded.hideRightSidebar)
        assertTrue(reloaded.autoRevealEnabled)
    }

    /** The reverse direction: turning an edge off on a platform whose default is on. */
    @Test
    fun `switching an edge off survives a save and load elsewhere`() {
        val platform = FocusModeSettings.defaultsFor("Mac OS X")
        val chosen = platform.copy(hideTopBar = false, hideLeftSidebar = false)

        val reloaded =
            FocusModeSettings.decodeWithDefaults(
                managerJson.encodeToString(FocusModeSettings.serializer(), chosen),
                platform,
            )

        assertEquals(chosen, reloaded)
    }

    private companion object {
        /**
         * The encoder the manager actually writes with, not a copy of its settings. A mirror
         * here would keep passing after someone dropped `encodeDefaults` from the real one,
         * which is precisely the bug these two tests exist to catch.
         */
        val managerJson = FocusModeSettings.storageJson
    }

    // endregion
}
