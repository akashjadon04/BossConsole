package ai.rever.boss.components.home

import androidx.compose.ui.graphics.painter.Painter

/**
 * Fetches a plugin's `icon_url` and decodes it for display, or null if it cannot be had.
 *
 * `expect`/`actual` for the same reason `loadFaviconFromCache` is: decoding an image needs a
 * platform toolkit (`ImageIO` on desktop). Modeled on `HighQualityFaviconService`, which already
 * does fetch-then-decode-then-cache for favicons.
 *
 * Null rather than throwing for every failure - unreachable host, 404, a body that is not an
 * image, a URL that is not http(s). The tile then shows the plugin's initials, which is also what
 * it shows for the far more common case of a blank `icon_url`.
 */
expect suspend fun loadPluginIcon(iconUrl: String): Painter?
