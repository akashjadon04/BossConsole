package ai.rever.boss.plugin.browser

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The four browser settings that only exist as system properties.
 *
 * fluck-browser renders the toolbar and owns credential fill, and it loads in a separate
 * classloader that cannot see [BrowserSettings]. The mirror is the whole mechanism: without it the
 * Settings toggle flips a field nothing reads, which is a switch that appears to work and does
 * nothing. That has happened once already - `discretePasswordFill` was orphaned by the fill moving
 * out of the host.
 *
 * Two properties of the mirror are worth pinning, and they fail differently:
 *
 * - **A setter publishes.** Miss it and the toggle is dead.
 * - **The defaults are published up front.** A Kotlin property initializer does NOT run through the
 *   custom setter, so without the `init` block the property is simply absent until the settings
 *   file loads - and on a first launch, or after a failed load, it never does. That matters most
 *   for the two that default to ON, because a plugin reading an absent property has to guess which
 *   way an unset value falls.
 *
 * PROCESS-GLOBAL: [BrowserSettings] is a singleton for the whole test JVM and these properties are
 * real system properties, so [restore] puts back what it changed. Correct today because desktopTest
 * runs one class at a time.
 */
class BrowserSettingsMirrorTest {
    private val mirrored =
        listOf(
            BrowserSettings.SUGGEST_PASSWORDS_PROP,
            BrowserSettings.OFFER_TO_SAVE_PASSWORDS_PROP,
            BrowserSettings.DISCRETE_PASSWORD_FILL_PROP,
            BrowserSettings.SHOW_SHARE_BUTTON_PROP,
        )

    // Reading these two is also what forces BrowserSettings to initialize, which is when the
    // defaults get published. Note the property NAMES above cannot do that: they are `const val`,
    // so the compiler inlines the literal and the object is never touched.
    private val savedSuggest = BrowserSettings.suggestPasswords
    private val savedOffer = BrowserSettings.offerToSavePasswords

    /**
     * Restores through the setters, and deliberately does NOT snapshot-and-restore the raw system
     * properties.
     *
     * Snapshotting them was the first attempt and it broke the suite: the snapshot is taken during
     * field initialization, before `BrowserSettings` has necessarily initialized, so it captured
     * four nulls - and restoring nulls *cleared* the published defaults. The object initializes
     * once per JVM, so nothing republished them and every later test in the class saw an unset
     * property. Restoring through the setters is exact, because the setters are the only thing that
     * writes these properties.
     */
    @AfterTest
    fun restore() {
        BrowserSettings.suggestPasswords = savedSuggest
        BrowserSettings.offerToSavePasswords = savedOffer
    }

    @Test
    fun `every mirrored setting has published a value by the time anything can read it`() {
        val unpublished = mirrored.filter { System.getProperty(it) == null }
        assertEquals(
            emptyList(),
            unpublished,
            "no default published for $unpublished - a plugin reading it has to guess, and an " +
                "absent value reads as 'off' in the obvious `== \"true\"` check",
        )
    }

    @Test
    fun `the persisted defaults agree with the in-memory ones`() {
        // BrowserSettingsData and BrowserSettings each declare these defaults as their own literal,
        // with nothing tying them together. A mismatch does not fail anywhere: it flips the default
        // the first time settings are written and read back, which looks like the user's own choice.
        val persisted = BrowserSettingsData()
        assertEquals(BrowserSettings.suggestPasswords, persisted.suggestPasswords)
        assertEquals(BrowserSettings.offerToSavePasswords, persisted.offerToSavePasswords)
        assertEquals(BrowserSettings.discretePasswordFill, persisted.discretePasswordFill)
        assertEquals(BrowserSettings.showShareButton, persisted.showShareButton)
    }

    @Test
    fun `the two password-manager settings default to on`() {
        assertEquals("true", System.getProperty(BrowserSettings.SUGGEST_PASSWORDS_PROP))
        assertEquals("true", System.getProperty(BrowserSettings.OFFER_TO_SAVE_PASSWORDS_PROP))
    }

    @Test
    fun `turning a password-manager setting off reaches the property the plugin reads`() {
        BrowserSettings.suggestPasswords = false
        assertEquals("false", System.getProperty(BrowserSettings.SUGGEST_PASSWORDS_PROP))

        BrowserSettings.offerToSavePasswords = false
        assertEquals("false", System.getProperty(BrowserSettings.OFFER_TO_SAVE_PASSWORDS_PROP))

        BrowserSettings.suggestPasswords = true
        assertEquals("true", System.getProperty(BrowserSettings.SUGGEST_PASSWORDS_PROP))
    }
}
