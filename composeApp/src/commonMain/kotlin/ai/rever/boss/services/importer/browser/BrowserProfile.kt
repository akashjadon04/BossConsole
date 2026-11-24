package ai.rever.boss.services.importer.browser

import java.io.File

/** Families of browser that share a storage layout. */
enum class BrowserFamily {
    /** Chrome, Edge, Brave, Vivaldi, Opera, Arc, plain Chromium … */
    CHROMIUM,
    FIREFOX,
    SAFARI,
}

/**
 * One installed browser profile found on this machine.
 *
 * A browser can have several ("Default", "Profile 1", named Firefox profiles),
 * and each holds its own bookmarks and logins.
 */
data class BrowserProfile(
    val browserName: String,
    val family: BrowserFamily,
    /** Profile label, or null when the browser has only one. */
    val profileName: String?,
    val directory: File,
) {
    /** What to show in the picker. */
    val displayName: String
        get() = if (profileName.isNullOrBlank()) browserName else "$browserName - $profileName"

    /** Stable identity for selection state. */
    val id: String get() = "$browserName:${profileName.orEmpty()}"
}

/** What a profile turned out to be able to give us. */
data class BrowserCapabilities(
    val bookmarkCount: Int?,
    val passwordCount: Int?,
    /** Why bookmarks are unavailable, when they are. */
    val bookmarkNote: String? = null,
    /** Why passwords are unavailable, when they are. */
    val passwordNote: String? = null,
)

/** A detected profile plus what it can offer. */
data class DetectedBrowser(
    val profile: BrowserProfile,
    val capabilities: BrowserCapabilities,
)
