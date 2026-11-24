package ai.rever.boss.filetypes

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Round-trip tests for the persisted default-apps decisions.
 *
 * `promptShown` is the only thing keeping the first-run offer to one appearance,
 * and `declinedCategories` is what stops "Set all" re-claiming a refusal - both
 * worth a test, and both previously untested.
 */
class DefaultAppsSettingsManagerTest {
    @TempDir
    lateinit var dir: File

    private lateinit var original: File

    @BeforeEach
    fun pointAtTempFile() {
        original = DefaultAppsSettingsManager.settingsFile
        DefaultAppsSettingsManager.settingsFile = File(dir, "default-apps.json")
        DefaultAppsSettingsManager.resetForTest()
    }

    @AfterEach
    fun restore() {
        DefaultAppsSettingsManager.settingsFile = original
        DefaultAppsSettingsManager.resetForTest()
    }

    private fun reload() {
        DefaultAppsSettingsManager.resetForTest()
        runBlocking { DefaultAppsSettingsManager.ensureLoaded() }
    }

    @Test
    fun `a fresh install offers the prompt`() {
        reload()
        assertTrue(DefaultAppsSettingsManager.shouldOfferPrompt())
        assertTrue(DefaultAppsSettingsManager.declinedCategories().isEmpty())
    }

    @Test
    fun `promptShown survives a reload, so the offer is made once`() {
        runBlocking {
            DefaultAppsSettingsManager.ensureLoaded()
            DefaultAppsSettingsManager.markPromptShown()
        }
        reload()
        assertFalse(DefaultAppsSettingsManager.shouldOfferPrompt())
    }

    @Test
    fun `declines survive a reload and accumulate`() {
        runBlocking {
            DefaultAppsSettingsManager.ensureLoaded()
            DefaultAppsSettingsManager.markDeclined(listOf("markdown"))
            DefaultAppsSettingsManager.markDeclined(listOf("shell-scripts", "markdown"))
        }
        reload()
        assertEquals(setOf("markdown", "shell-scripts"), DefaultAppsSettingsManager.declinedCategories())
    }

    @Test
    fun `clearing a decline is what lets an explicit Set stick`() {
        runBlocking {
            DefaultAppsSettingsManager.ensureLoaded()
            DefaultAppsSettingsManager.markDeclined(listOf("markdown", "shell-scripts"))
            DefaultAppsSettingsManager.clearDeclined(listOf("markdown"))
        }
        reload()
        assertEquals(setOf("shell-scripts"), DefaultAppsSettingsManager.declinedCategories())
    }

    @Test
    fun `a corrupt file falls back to defaults rather than throwing`() {
        DefaultAppsSettingsManager.settingsFile.writeText("{ this is not json")
        reload()
        // Defaults means the prompt may be offered again, which is the better
        // failure direction: a corrupt file that suppressed it forever would leave
        // no way to discover the feature.
        assertTrue(DefaultAppsSettingsManager.shouldOfferPrompt())
        assertTrue(DefaultAppsSettingsManager.declinedCategories().isEmpty())
    }

    @Test
    fun `an unknown field does not empty the file`() {
        // A future build adding a field must not make this build read an empty
        // decision set - the strict-Json trap this repo records.
        DefaultAppsSettingsManager.settingsFile.writeText(
            """{"promptShown": true, "declinedCategories": ["markdown"], "somethingNew": 7}""",
        )
        reload()
        assertFalse(DefaultAppsSettingsManager.shouldOfferPrompt())
        assertEquals(setOf("markdown"), DefaultAppsSettingsManager.declinedCategories())
    }
}
