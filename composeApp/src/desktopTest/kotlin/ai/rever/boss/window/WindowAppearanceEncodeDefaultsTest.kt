package ai.rever.boss.window

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the one serialiser flag three chrome defaults now depend on.
 *
 * The title row and both icon strips were flipped off by changing their class defaults, with no
 * migration step. That only reaches an existing install because the manager omits default-valued
 * fields when it writes: their old shipped values equalled the old class defaults, so no file ever
 * named them, so a file decodes them at whatever the current default is.
 *
 * Switch `encodeDefaults` on - for an unrelated field, in a year, reasonably - and every file
 * written from then on names all three, freezing whoever has it on the old chrome. Nothing else in
 * the codebase would fail, and the effect would only show up on the release after the one that
 * changed it.
 */
class WindowAppearanceEncodeDefaultsTest {
    private val json = WindowAppearanceSettingsManager.json

    @Test
    fun `a defaults-equal object writes no keys at all`() {
        val encoded = json.encodeToString(WindowAppearanceSettings.serializer(), WindowAppearanceSettings())
        assertEquals("{}", encoded.replace(Regex("\\s"), ""), "encodeDefaults must stay false")
    }

    @Test
    fun `the three flipped flags are absent unless they differ from the default`() {
        val encoded = json.encodeToString(WindowAppearanceSettings.serializer(), WindowAppearanceSettings())
        listOf("showTitleBar", "showLeftStrip", "showRightStrip").forEach {
            assertFalse(encoded.contains(it), "$it must not be written when it equals the default")
        }
    }

    @Test
    fun `a value that differs is written`() {
        // The other half of the contract: omitting defaults must not mean omitting choices.
        val encoded =
            json.encodeToString(
                WindowAppearanceSettings.serializer(),
                WindowAppearanceSettings(showLeftStrip = true),
            )
        assertTrue(encoded.contains("showLeftStrip"), "a non-default value must survive a write")
    }

    @Test
    fun `an absent key decodes to the current default`() {
        // What makes a default flip reach an existing install at all.
        val decoded = json.decodeFromString(WindowAppearanceSettings.serializer(), "{}")
        assertEquals(WindowAppearanceSettings(), decoded)
    }
}
