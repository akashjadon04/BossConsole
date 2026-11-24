package ai.rever.boss.utils

/** Bundle identifier shared by macOS runtime integrations. */
internal const val BOSS_MACOS_BUNDLE_ID = "ai.rever.boss"

/**
 * Bundle identifier of the branded Chromium **engine**, not the app.
 *
 * `~/.boss/boss-chromium/BOSS.app` is a full Chromium app bundle produced by
 * `build-chromium-branding.yml` from `chromium-branding/params.json`, where
 * `mac.bundle.id` is this value and `mac.bundle.name` is "BOSS". Until that
 * workflow learned to strip them, it also inherited Chromium's own
 * `CFBundleURLTypes` (http, https) and `CFBundleDocumentTypes` (public.html),
 * which made Launch Services offer **two** candidates called "BOSS" that no
 * user could tell apart - and picking the wrong one handed every link to a bare
 * rendering engine with no BOSS window in sight.
 *
 * Kept as a named constant rather than a literal at the comparison site because
 * three places have to agree about it: the status check, the repair, and the
 * copy that tells the user what happened.
 */
internal const val BOSS_MACOS_ENGINE_BUNDLE_ID = "ai.rever.boss.browser"

/** Shipping app-bundle name used by the default `/Applications` fallback. */
internal const val BOSS_MACOS_APP_BUNDLE_NAME = "BOSS.app"
