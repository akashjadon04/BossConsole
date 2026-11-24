package ai.rever.boss.theme

import ai.rever.boss.plugin.ui.BossThemes
import ai.rever.boss.utils.SystemUtils
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Locks down the platform default theme and, more importantly, what an existing
 * settings file means once the default stops being the same everywhere.
 *
 * Both branches are driven through the explicit `isWindows` parameter rather
 * than the running host, so the Windows behaviour is covered on every CI leg
 * and not only on `windows-latest`.
 */
class AppThemeDefaultsTest {
    @Test
    fun `windows opens light and mac and linux open on the unix default`() {
        assertEquals(BossThemes.WINDOWS_DEFAULT_ID, BossThemes.defaultIdFor(isWindows = true))
        assertEquals(BossThemes.UNIX_DEFAULT_ID, BossThemes.defaultIdFor(isWindows = false))
    }

    /**
     * The Windows default is asserted as a *property* (light) because the request
     * behind it was "light on Windows". This one is asserted as an *id*, because
     * the request behind it named a theme: "make NVIDIA the default on mac". Every
     * other test here routes through [BossThemes.UNIX_DEFAULT_ID] and so would
     * still pass if the constant were re-pointed at any other dark theme - this is
     * the only thing that pins which one.
     */
    @Test
    fun `mac and linux open on nvidia specifically`() {
        assertEquals("nvidia", BossThemes.UNIX_DEFAULT_ID)
        assertEquals("nvidia", BossThemes.defaultIdFor(isWindows = false))
    }

    /**
     * [BossThemes.DEFAULT_ID] is no longer any platform's first run, and that is
     * the point: it is what a file saying `{}` means. If someone "tidies up" by
     * re-pointing it at the new platform default, every user whose stored choice
     * was written by the old `encodeDefaults = false` writer is silently
     * re-skinned, on all three platforms at once.
     */
    @Test
    fun `the class default is not what any platform opens with`() {
        assertNotEquals(BossThemes.DEFAULT_ID, BossThemes.defaultIdFor(isWindows = false))
        assertNotEquals(BossThemes.DEFAULT_ID, BossThemes.defaultIdFor(isWindows = true))
    }

    /**
     * The request was "light on Windows", not "this particular id", so assert the
     * property rather than only the name. A future re-point of
     * [BossThemes.WINDOWS_DEFAULT_ID] at a dark theme is the failure worth catching.
     */
    @Test
    fun `the windows default is a registered light theme`() {
        val windowsDefault = BossThemes.byId(BossThemes.defaultIdFor(isWindows = true))

        assertEquals(BossThemes.WINDOWS_DEFAULT_ID, windowsDefault.id)
        assertTrue(windowsDefault.isLight, "Windows must open on a light theme")
        assertFalse(
            BossThemes.byId(BossThemes.defaultIdFor(isWindows = false)).isLight,
            "mac and Linux must open on a dark theme",
        )
    }

    /** Guards a typo'd id, which `byId` would otherwise absorb into the fallback. */
    @Test
    fun `both platform defaults name themes that actually exist`() {
        val ids = BossThemes.all.map { it.id }

        assertTrue(BossThemes.DEFAULT_ID in ids)
        assertTrue(BossThemes.WINDOWS_DEFAULT_ID in ids)
        assertTrue(BossThemes.UNIX_DEFAULT_ID in ids)
    }

    @Test
    fun `this host is wired to its own platform branch`() {
        assertEquals(
            BossThemes.defaultIdFor(SystemUtils.isWindows),
            AppThemeSettings.defaultsFor(SystemUtils.isWindows).appThemeId,
        )
    }

    @Test
    fun `a first run with no settings file opens on the platform default`() {
        assertEquals(
            BossThemes.WINDOWS_DEFAULT_ID,
            AppThemeSettings.decodeOrDefaults(content = null, isWindows = true).appThemeId,
        )
        assertEquals(
            BossThemes.UNIX_DEFAULT_ID,
            AppThemeSettings.decodeOrDefaults(content = null, isWindows = false).appThemeId,
        )
    }

    /**
     * The regression this feature would otherwise ship. The file is written only
     * by `select()`, and the old writer omitted the id when it equalled the class
     * default, so `{}` on disk is a Windows user who deliberately picked
     * Blueprint. Resolving that absence from the platform default flips them to
     * light on the very next launch, with no way to make it stick.
     */
    @Test
    fun `an existing file that omits the id keeps the class default on every platform`() {
        assertEquals(
            BossThemes.DEFAULT_ID,
            AppThemeSettings.decodeOrDefaults(content = "{}", isWindows = true).appThemeId,
        )
        // The same exposure the Windows default created, now live on macOS and
        // Linux: `{}` is a mac user who deliberately picked Blueprint under the
        // old writer, and resolving it from the platform default would flip them
        // to NVIDIA on the very next launch with no way to make Blueprint stick.
        assertEquals(
            BossThemes.DEFAULT_ID,
            AppThemeSettings.decodeOrDefaults(content = "{}", isWindows = false).appThemeId,
        )
    }

    @Test
    fun `a stored id always wins over the platform default`() {
        val stored = """{"appThemeId":"operator"}"""

        assertEquals("operator", AppThemeSettings.decodeOrDefaults(stored, isWindows = true).appThemeId)
        assertEquals("operator", AppThemeSettings.decodeOrDefaults(stored, isWindows = false).appThemeId)
    }

    /**
     * With `encodeDefaults = false` a Windows user choosing Blueprint wrote `{}`,
     * which the next launch read back as the platform default. The round trip has
     * to survive the one value that used to disappear.
     */
    @Test
    fun `choosing the dark default on windows round-trips`() {
        val written =
            AppThemeSettings.storageJson.encodeToString(
                AppThemeSettings.serializer(),
                AppThemeSettings(appThemeId = BossThemes.DEFAULT_ID),
            )

        assertTrue("appThemeId" in written, "the id must be written even when it equals the class default")
        assertEquals(
            BossThemes.DEFAULT_ID,
            AppThemeSettings.decodeOrDefaults(written, isWindows = true).appThemeId,
        )
    }

    /** Unknown keys are the shape of a file written by a newer build, or by BossConsoleRust. */
    @Test
    fun `an unknown key does not throw away the stored theme`() {
        val stored = """{"appThemeId":"daylight","futureKey":true}"""

        assertEquals("daylight", AppThemeSettings.decodeOrDefaults(stored, isWindows = true).appThemeId)
    }
}
