package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests the per-platform identity mapping behind `Settings > Browser`'s status.
 *
 * The card used to hold a `Boolean?`, which cannot tell "Safari holds http" from
 * "a BOSS component holds http" - and the second is the state every install
 * created before the branded Chromium engine stopped declaring
 * `CFBundleURLTypes`. Flattened, it said "BOSS is not your default browser" to
 * users who had set it, while `Settings > Default Apps` reported the same machine
 * as `OurEngine` and offered a Repair. Two screens, one machine, opposite stories.
 *
 * The macOS half needs no mapping test of its own: it reads bundle ids and
 * `DefaultHandlerState.of` already owns that comparison ([DefaultHandlerStateTest]).
 * Windows and Linux identify BOSS by a ProgId and a desktop-entry name, so each
 * has a mapping this pins - extracted from the `reg query` / `xdg-settings` calls
 * for that reason.
 */
class BrowserHandlerStateTest {
    // ---- Windows ----

    @Test
    fun `the BOSS ProgId is ours, whatever its case`() {
        assertEquals(DefaultHandlerState.Ours, WindowsDefaultBrowserHandler.stateForProgId("BOSS"))
        // The registry preserves the case of whatever wrote the value, so a
        // case-sensitive comparison would report BOSS as another vendor's browser.
        assertEquals(DefaultHandlerState.Ours, WindowsDefaultBrowserHandler.stateForProgId("boss"))
    }

    @Test
    fun `another browser's ProgId names itself`() {
        assertEquals(
            DefaultHandlerState.Other("ChromeHTML"),
            WindowsDefaultBrowserHandler.stateForProgId("ChromeHTML"),
        )
        assertFalse(WindowsDefaultBrowserHandler.stateForProgId("FirefoxURL").isOurs)
    }

    @Test
    fun `no recorded choice is not ours`() {
        assertEquals(DefaultHandlerState.Other(null), WindowsDefaultBrowserHandler.stateForProgId(null))
    }

    @Test
    fun `a ProgId that merely starts with BOSS is another application`() {
        // `BOSS.md` is a real ProgId this repo writes, for the file-type
        // associations. A `startsWith` comparison anywhere in this chain would
        // report the markdown association as the browser default.
        assertEquals(DefaultHandlerState.Other("BOSS.md"), WindowsDefaultBrowserHandler.stateForProgId("BOSS.md"))
    }

    // ---- Linux ----

    @Test
    fun `the BOSS desktop entry is ours, whatever its case`() {
        assertEquals(DefaultHandlerState.Ours, LinuxDefaultBrowserHandler.stateForDesktopEntry("boss.desktop"))
        assertEquals(DefaultHandlerState.Ours, LinuxDefaultBrowserHandler.stateForDesktopEntry("BOSS.desktop"))
    }

    @Test
    fun `another desktop entry names itself, and nothing set is not ours`() {
        assertEquals(
            DefaultHandlerState.Other("firefox.desktop"),
            LinuxDefaultBrowserHandler.stateForDesktopEntry("firefox.desktop"),
        )
        assertEquals(DefaultHandlerState.Other(null), LinuxDefaultBrowserHandler.stateForDesktopEntry(null))
    }

    // ---- the fold both platforms hand their per-scheme answers to ----

    @Test
    fun `http and https disagreeing is not ours`() {
        // What the old Windows code said as `http == "BOSS" && https == "BOSS"`.
        // Rewriting it as a reduce over states must not have loosened it: Launch
        // Services and the registry store the two schemes separately, and setting
        // one can succeed while the other fails.
        val states =
            listOf(
                WindowsDefaultBrowserHandler.stateForProgId("BOSS"),
                WindowsDefaultBrowserHandler.stateForProgId("ChromeHTML"),
            )
        val reduced = DefaultHandlerState.reduce(states)

        assertFalse(reduced.isOurs, "one scheme pointing elsewhere still means BOSS is not the browser")
        assertEquals(DefaultHandlerState.Other("ChromeHTML"), reduced)
    }

    @Test
    fun `both schemes pointing at BOSS is ours`() {
        val reduced =
            DefaultHandlerState.reduce(
                listOf(
                    WindowsDefaultBrowserHandler.stateForProgId("BOSS"),
                    WindowsDefaultBrowserHandler.stateForProgId("BOSS"),
                ),
            )

        assertTrue(reduced.isOurs)
        assertEquals(DefaultHandlerState.Ours, reduced)
    }
}
