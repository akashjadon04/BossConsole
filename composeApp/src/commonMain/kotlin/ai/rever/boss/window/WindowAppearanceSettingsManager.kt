package ai.rever.boss.window

import kotlinx.coroutines.flow.StateFlow

/**
 * Expect declaration for window appearance settings manager.
 * Platform-specific implementations handle persistence and default values.
 */
expect object WindowAppearanceSettingsManager {
    /**
     * Current settings state flow
     */
    val currentSettings: StateFlow<WindowAppearanceSettings>

    /**
     * Update window appearance settings and save to disk asynchronously.
     */
    suspend fun updateSettings(settings: WindowAppearanceSettings)

    /**
     * Get default settings for the current platform.
     *
     * No longer differs by platform: the title row is off everywhere. See
     * `WindowAppearanceSettings.showTitleBar`.
     */
    fun getDefaultSettings(): WindowAppearanceSettings
}
