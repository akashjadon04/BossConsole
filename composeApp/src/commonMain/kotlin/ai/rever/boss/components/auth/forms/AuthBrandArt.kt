package ai.rever.boss.components.auth.forms

import ai.rever.boss.plugin.ui.BossTheme
import ai.rever.boss.plugin.ui.BossThemeController
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The brand side of the sign-in screen, drawn natively.
 *
 * Every value comes from bossconsole.ai's own stylesheet **by way of the design system**: the
 * Blueprint theme's KDoc records that its tokens are "either lifted verbatim from the site's
 * stylesheet or composited from it". Going through the tokens rather than hardcoding `#05070b` and
 * `#0f5bff` is what makes this read as the site in the default theme *and* stay coherent in the other
 * four - Operator's signal is amber, and two of the five themes are light.
 *
 * What is reproduced, with the rule it comes from:
 *  - 24px minor / 72px major line grid - `background-size: 24px 24px` and `72px 72px`, drawn there as
 *    `linear-gradient(90deg, #ffffff0a 1px, transparent 1px)`;
 *  - radial signal glow - `radial-gradient(circle, #0f5bff5c 0, #0f5bff1f 38%, transparent 68%)`;
 *  - dot lattice - `radial-gradient(circle, #ffffff21 1px, transparent 1.2px)`;
 *  - vignette to the floor colour - `linear-gradient(#000 0, #000c 55%, transparent 88%)`.
 *
 * Alphas are expressed against theme tokens rather than white, so the grid is a dark hairline on the
 * light themes instead of an invisible one.
 */
@Composable
internal fun AuthBrandArt(modifier: Modifier) {
    val colors = BossTheme.colors
    val isLight = BossThemeController.current.isLight
    // On unless a deployment turned it off; see authBrandSiteEnabled for what it costs and how.
    val siteEnabled = authBrandSiteActive()
    var siteReady by remember { mutableStateOf(false) }
    var siteFailed by remember { mutableStateOf(false) }

    Box(modifier = modifier.background(colors.ink), contentAlignment = Alignment.Center) {
        // The art is always composed, underneath, and is what shows until the site says it has
        // finished loading - and forever if it never does. That ordering is the whole fallback: there
        // is no state in which this pane is empty.
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawSignalGlow(colors.signal, isLight)
            drawBlueprintGrid(colors.textPrimary, isLight)
            drawFloorVignette(colors.ink)
        }
        if (!siteReady) {
            BrandCopy()
        }
        if (siteEnabled && !siteFailed) {
            AuthBrandSite(
                onReady = { siteReady = true },
                onFailed = { siteFailed = true },
            )
        }
    }
}

/**
 * The site's hero glow: signal at the centre, gone by two thirds out.
 *
 * Placed off-centre and above the middle, as on the site, so the wordmark sits in the bright part
 * rather than at the pane's geometric centre.
 */
private fun DrawScope.drawSignalGlow(
    signal: Color,
    isLight: Boolean,
) {
    drawRect(
        brush =
            Brush.radialGradient(
                colorStops =
                    arrayOf(
                        0.00f to signal.copy(alpha = if (isLight) GLOW_ALPHA_LIGHT else GLOW_ALPHA_DARK),
                        0.38f to signal.copy(alpha = if (isLight) 0.05f else 0.12f),
                        0.68f to Color.Transparent,
                    ),
                center = Offset(size.width * 0.42f, size.height * 0.40f),
                radius = maxOf(size.width, size.height) * 0.62f,
            ),
        size = size,
    )
}

/**
 * Two stacked lattices: a hairline grid every [MINOR_GRID], emphasised every [MAJOR_GRID], with a dot
 * at each major intersection. The site draws the same thing as two background layers at 24px and 72px.
 */
private fun DrawScope.drawBlueprintGrid(
    inkContrast: Color,
    isLight: Boolean,
) {
    val minor = MINOR_GRID.toPx()
    val minorInk = inkContrast.copy(alpha = if (isLight) 0.05f else 0.04f)
    val majorInk = inkContrast.copy(alpha = if (isLight) 0.09f else 0.07f)

    // Emphasis is decided by an integer INDEX, not by testing an accumulated float for divisibility.
    // The previous version walked `x += minor` and asked `x % major < 1f`: at fractional densities the
    // running total drifts, so a line that should be major can land just below a multiple of `major`,
    // the modulo comes out near `major` instead of near zero, and the emphasis silently renders as a
    // hairline. Counting lines is exact at any density, and drops two modulos per line as well.
    val majorEvery = (MAJOR_GRID / MINOR_GRID).toInt()

    var column = 0
    while (column * minor <= size.width) {
        val x = column * minor
        drawLine(
            color = if (column % majorEvery == 0) majorInk else minorInk,
            start = Offset(x, 0f),
            end = Offset(x, size.height),
            strokeWidth = 1f,
        )
        column++
    }
    var row = 0
    while (row * minor <= size.height) {
        val y = row * minor
        drawLine(
            color = if (row % majorEvery == 0) majorInk else minorInk,
            start = Offset(0f, y),
            end = Offset(size.width, y),
            strokeWidth = 1f,
        )
        row++
    }

    // Same index-based walk as the lines, so the dots land exactly on major intersections rather than
    // drifting away from them across a wide pane.
    val dot = inkContrast.copy(alpha = if (isLight) 0.16f else 0.13f)
    val dotRadius = 1.dp.toPx()
    val majorStep = majorEvery * minor
    var dotColumn = 0
    while (dotColumn * majorStep <= size.width) {
        var dotRow = 0
        while (dotRow * majorStep <= size.height) {
            drawCircle(
                color = dot,
                radius = dotRadius,
                center = Offset(dotColumn * majorStep, dotRow * majorStep),
            )
            dotRow++
        }
        dotColumn++
    }
}

/** Fades the lattice into the floor colour top and bottom, so it never meets an edge as a hard cut. */
private fun DrawScope.drawFloorVignette(ink: Color) {
    drawRect(
        brush =
            Brush.verticalGradient(
                colorStops =
                    arrayOf(
                        0.00f to ink,
                        0.18f to Color.Transparent,
                        0.72f to Color.Transparent,
                        1.00f to ink,
                    ),
            ),
        size = size,
    )
}

/**
 * The wordmark and positioning copy.
 *
 * Centred in the pane rather than pinned to its left edge - a small mark in the corner of a very wide
 * panel is what read as empty. The headline and description are **verbatim from the site**, so the
 * product does not describe itself two different ways in two places.
 *
 * **Typographic, with no app icon, on purpose.** `boss_icon.png` is an app-launcher tile: a black
 * rounded square with the word BOSS set inside it. On this canvas that fails twice - its near-black
 * fill has no edge against `ink`, so it reads as a hole rather than a mark, and because the wordmark
 * is *inside* the image, pairing it with a "BOSS" label said the name twice. The mono eyebrow is the
 * wordmark here, which is also how the site's own hero is built.
 */
@Composable
private fun BrandCopy() {
    val colors = BossTheme.colors
    val space = BossTheme.space
    Column(modifier = Modifier.widthIn(max = BRAND_COLUMN_WIDTH).padding(space.xxl)) {
        Text(text = "BOSS CONSOLE", style = BossTheme.type.label, color = colors.signalText)
        Spacer(modifier = Modifier.height(space.md))
        // The site's accent rule: a hairline fading out to the right.
        Box(
            modifier =
                Modifier
                    .width(ACCENT_RULE_WIDTH)
                    .height(1.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(colors.textPrimary.copy(alpha = 0.5f), Color.Transparent),
                        ),
                    ),
        )
        Spacer(modifier = Modifier.height(space.xl))
        Text(
            text = "The governed workspace for AI agents",
            style = BossTheme.type.displayLarge,
            color = colors.textPrimary,
        )
        Spacer(modifier = Modifier.height(space.md))
        Text(
            text =
                "Give AI agents real tools to get real work done, while you stay in control of " +
                    "every capability, credential, and action.",
            style = BossTheme.type.body,
            color = colors.textSecondary,
        )
        Spacer(modifier = Modifier.height(space.xl))
        Text(text = "bossconsole.ai", style = BossTheme.type.micro, color = colors.textMuted)
    }
}

/** Minor grid pitch, from the site's `background-size: 24px 24px`. */
private val MINOR_GRID: Dp = 24.dp

/** Major grid pitch, from the site's `background-size: 72px 72px`. */
private val MAJOR_GRID: Dp = 72.dp

/** Centre alpha of the signal glow, from the site's `#0f5bff5c` (0x5c/0xff). */
private const val GLOW_ALPHA_DARK = 0.36f

/** Halved on the light themes, where the same alpha over white is a wash rather than a glow. */
private const val GLOW_ALPHA_LIGHT = 0.16f

/** Wide enough for the headline at `displayLarge` to break into two lines rather than four. */
private val BRAND_COLUMN_WIDTH: Dp = 560.dp
private val ACCENT_RULE_WIDTH: Dp = 96.dp
