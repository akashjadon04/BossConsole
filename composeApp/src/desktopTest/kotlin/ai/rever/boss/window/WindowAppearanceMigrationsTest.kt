package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Moving existing installs onto the left tab bar and the hidden top bar.
 *
 * Changing the DEFAULT alone would not have done it: the manager writes the whole object on every
 * save, so an existing file names both values explicitly and would keep them for ever. The new
 * defaults would have reached new installs only - which is not what "by default" means to someone
 * who already has BOSS open.
 */
class WindowAppearanceMigrationsTest {
    @Test
    fun `a current file is left alone`() {
        val current = WindowAppearanceSettings(settingsVersion = WindowAppearanceSettings.CURRENT_SETTINGS_VERSION)
        assertNull(WindowAppearanceMigrations.migrate(current, isMacOs = false))
    }

    @Test
    fun `an install on the old shipped defaults is moved`() {
        val old = WindowAppearanceSettings(showTopBar = true, tabBarPosition = TabBarPosition.TOP, settingsVersion = 0)
        val migrated = WindowAppearanceMigrations.migrate(old, isMacOs = false)!!

        assertEquals(false, migrated.showTopBar)
        assertEquals(TabBarPosition.LEFT, migrated.tabBarPosition)
    }

    @Test
    fun `someone already on the left bar keeps every other choice`() {
        // Not on the old defaults, so nothing is decided for them - including the top bar they
        // chose to keep.
        val chosen =
            WindowAppearanceSettings(
                showTopBar = true,
                tabBarPosition = TabBarPosition.LEFT,
                settingsVersion = 0,
            )
        val migrated = WindowAppearanceMigrations.migrate(chosen, isMacOs = false)!!

        assertEquals(true, migrated.showTopBar)
        assertEquals(TabBarPosition.LEFT, migrated.tabBarPosition)
    }

    @Test
    fun `someone who hid the top bar but kept top tabs is left alone`() {
        val chosen =
            WindowAppearanceSettings(
                showTopBar = false,
                tabBarPosition = TabBarPosition.TOP,
                settingsVersion = 0,
            )
        val migrated = WindowAppearanceMigrations.migrate(chosen, isMacOs = false)!!

        assertEquals(false, migrated.showTopBar)
        assertEquals(TabBarPosition.TOP, migrated.tabBarPosition)
    }

    @Test
    fun `an out-of-date file is stamped even when nothing else changes`() {
        // Otherwise the step re-runs on every launch and would keep re-deciding for someone who
        // moved back afterwards.
        val chosen = WindowAppearanceSettings(showTopBar = false, tabBarPosition = TabBarPosition.TOP)
        val migrated = WindowAppearanceMigrations.migrate(chosen, isMacOs = false)!!

        assertEquals(WindowAppearanceSettings.CURRENT_SETTINGS_VERSION, migrated.settingsVersion)
        assertNull(WindowAppearanceMigrations.migrate(migrated, isMacOs = false))
    }

    @Test
    fun `every other appearance choice survives the move`() {
        val old =
            WindowAppearanceSettings(
                showTopBar = true,
                tabBarPosition = TabBarPosition.TOP,
                showBottomBar = false,
                tabBarVerticalWidth = 260f,
                tabBarCollapsed = true,
                settingsVersion = 0,
            )
        val migrated = WindowAppearanceMigrations.migrate(old, isMacOs = false)!!

        assertEquals(false, migrated.showBottomBar)
        assertEquals(260f, migrated.tabBarVerticalWidth)
        assertTrue(migrated.tabBarCollapsed)
    }

    @Test
    fun `a fresh install comes up on the new defaults`() {
        val fresh = WindowAppearanceSettings()

        assertEquals(false, fresh.showTopBar)
        assertEquals(TabBarPosition.LEFT, fresh.tabBarPosition)
    }

    // --- 1 -> 2: the title bar comes back on macOS --------------------------------------------

    @Test
    fun `an existing macOS install gets the title bar back`() {
        // The whole point of the step. A file written by 9.4.x does not mention showTitleBar at
        // all - it equalled the class default, so `encodeDefaults = false` never wrote it - which
        // means a default flip alone would leave this install with the row off for ever.
        val existing = WindowAppearanceSettings(settingsVersion = 1)
        val migrated = WindowAppearanceMigrations.migrate(existing, isMacOs = true)!!

        assertTrue(migrated.showTitleBar)
        assertEquals(WindowAppearanceSettings.CURRENT_SETTINGS_VERSION, migrated.settingsVersion)
    }

    @Test
    fun `Windows and Linux keep the row off`() {
        // The row is an ordinary bar there and the OS draws its own frame, so there is nothing for
        // it to hold. This is why the class default stays false and the branch lives in the step.
        val existing = WindowAppearanceSettings(settingsVersion = 1)
        val migrated = WindowAppearanceMigrations.migrate(existing, isMacOs = false)!!

        assertEquals(false, migrated.showTitleBar)
    }

    @Test
    fun `the title-bar step does not re-decide the tab bar`() {
        // A file already on version 1 has been through 0 -> 1. Someone who chose the top bar back
        // must keep it: only the brand new step applies to them.
        val chose =
            WindowAppearanceSettings(
                showTopBar = true,
                tabBarPosition = TabBarPosition.TOP,
                settingsVersion = 1,
            )
        val migrated = WindowAppearanceMigrations.migrate(chose, isMacOs = true)!!

        assertTrue(migrated.showTopBar, "their top bar was taken away by a step that had already run")
        assertEquals(TabBarPosition.TOP, migrated.tabBarPosition)
        assertTrue(migrated.showTitleBar)
    }

    @Test
    fun `a version 0 file gets both steps at once`() {
        val ancient =
            WindowAppearanceSettings(
                showTopBar = true,
                tabBarPosition = TabBarPosition.TOP,
                settingsVersion = 0,
            )
        val migrated = WindowAppearanceMigrations.migrate(ancient, isMacOs = true)!!

        assertEquals(false, migrated.showTopBar, "0 -> 1")
        assertEquals(TabBarPosition.LEFT, migrated.tabBarPosition, "0 -> 1")
        assertTrue(migrated.showTitleBar, "1 -> 2")
    }

    @Test
    fun `migrating twice is a no-op`() {
        val existing = WindowAppearanceSettings(settingsVersion = 1)
        val once = WindowAppearanceMigrations.migrate(existing, isMacOs = true)!!

        assertNull(WindowAppearanceMigrations.migrate(once, isMacOs = true), "the step must run at most once")
    }
}
