package ai.rever.boss.components.auth.forms

import androidx.compose.runtime.Composable

/**
 * The bundled page the brand panel shows, as a Compose resource path.
 *
 * **Local, not `https://bossconsole.ai`.** Loading the live site put a network fetch on the sign-in
 * path - the one screen a user cannot get past - so a blank pane was likeliest exactly when the
 * network was bad and someone was trying to sign in. It also meant a live, fully interactive page with
 * no browser chrome: one stray click navigated the panel away with no way back until relaunch. The
 * bundled copy has neither problem and cannot change under the app.
 *
 * It is three sections of the real site, using the site's own markup classes, copy and stylesheet -
 * see the comment at the top of `index.html` for exactly what was trimmed and the one deliberate
 * difference (no webfont fetch, so mono falls back to the platform's).
 */
internal const val AUTH_BRAND_PAGE = "files/auth-brand/index.html"

/**
 * The assets [AUTH_BRAND_PAGE] links, which have to be unpacked beside it to resolve.
 *
 * Both are referenced relatively by the page, so all three files land in one directory. Miss one and it
 * fails quietly in a different way each: without the stylesheet the sections render unstyled, without
 * the script the panel is simply static - which is why the page is written to be complete without it.
 */
internal val AUTH_BRAND_ASSETS =
    listOf(
        "files/auth-brand/site.css",
        "files/auth-brand/brand.js",
    )

/**
 * Whether the brand panel should show [AUTH_BRAND_PAGE] over the drawn art. **On by default.**
 *
 * It was opt-in while the panel loaded the live site, and bundling the page is what earned the default:
 * the two real objections were a network fetch on the sign-in path - the one screen a user cannot get
 * past - and a live interactive page with no browser chrome. Neither survives a local file.
 *
 * What remains is that rendering it starts a Chromium engine, which is why there is still a way to turn
 * it off: `BOSS_AUTH_BRAND_SITE=false` (or `-Dboss.auth.brand.site=false`). A deployment that does not
 * want an engine on its login screen sets that and gets `AuthBrandArt`, which is a complete panel.
 *
 * The art stays composed underneath regardless, so a disabled flag, an engine that will not start and a
 * page that fails to render all land on the same drawn panel rather than on nothing.
 */
@Composable
internal expect fun authBrandSiteEnabled(): Boolean

/**
 * Forces [authBrandSiteActive] one way, for tests. Null means "ask the environment".
 *
 * A layout test of the scaffold must not depend on whether a browser engine happens to start on the
 * machine running it - and now that the panel is on by default, `AuthScaffoldLayoutTest` would drag a
 * real Chromium view into a unit test and fail on `Can't obtain the display ID of a closed window`.
 * Setting the system property is not enough, because the environment variable takes precedence and a
 * developer may have it set. Same save-and-restore shape as `BossOverlayHost.useHeavyweightOverlays` in
 * `BossAlertDialogComposeTest`.
 *
 * **Set it before composing, not during.** It is a plain `var` rather than a `MutableState`, so
 * [authBrandSiteActive] reading it from a composable will not recompose when it changes - fine for a
 * `@Before` seam, useless as a runtime toggle.
 */
internal var authBrandSiteOverride: Boolean? = null

/** [authBrandSiteEnabled], with [authBrandSiteOverride] winning when a test has set it. */
@Composable
internal fun authBrandSiteActive(): Boolean = authBrandSiteOverride ?: authBrandSiteEnabled()

/**
 * Renders the bundled brand page, reporting when it is ready to be shown.
 *
 * [onReady] fires when the page has finished loading AND been positioned on its first section, NOT
 * when the view is created: the art has to stay up until there is something worth showing, or the
 * panel flashes empty for as long as Chromium takes to start and paint. [onFailed] covers an engine
 * that will not initialise and a page that cannot be unpacked, and both mean "keep the art".
 */
@Composable
internal expect fun AuthBrandSite(
    onReady: () -> Unit,
    onFailed: () -> Unit,
)
