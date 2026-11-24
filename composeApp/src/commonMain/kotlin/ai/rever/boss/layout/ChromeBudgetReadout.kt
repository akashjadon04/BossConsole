package ai.rever.boss.layout

import ai.rever.boss.focusmode.FocusModeSettings
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.window.WindowAppearanceSettings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val logger = BossLogger.forComponent("ChromeBudgetReadout")

/** Env var and system property that turn the readout on. See [chromeBudgetReadoutEnabled]. */
private const val ENV_FLAG = "BOSS_CHROME_BUDGET"
private const val PROPERTY_FLAG = "boss.chrome.budget"

/**
 * Whether the readout is on, given the raw [env] var and [property] values.
 *
 * Both are accepted, and a blank env var falls through to the property rather than shadowing it.
 * That is not hypothetical tidiness: `BrowserAnalytics.telemetryEnabledFrom` carries a KDoc
 * recording that the blank-env case was hit for real, which is why this rule is a named function
 * with a test rather than an expression inlined into a `by lazy` that no test can reach.
 *
 * The property matters most for the way this flag actually gets used: `./gradlew run` makes an env
 * var awkward to set, where `-Dboss.chrome.budget=1` just works.
 */
internal fun chromeBudgetReadoutEnabled(
    env: String?,
    property: String?,
): Boolean {
    val raw = env?.takeIf { it.isNotBlank() } ?: property
    return raw?.trim()?.lowercase() in TRUTHY
}

private val TRUTHY = setOf("1", "true", "yes", "on")

/**
 * Read once, not per composition: the flag is a developer's launch decision, and re-reading the
 * environment on every recomposition of every window would be pure waste.
 */
private val readoutEnabled: Boolean by lazy {
    chromeBudgetReadoutEnabled(System.getenv(ENV_FLAG), System.getProperty(PROPERTY_FLAG))
}

/**
 * How coarsely the window size is tracked for logging.
 *
 * Keying the effect on the exact size emits a line per pixel of a drag-resize, which buries the one
 * line the developer was watching for. The budget is what this exists to report, and it does not
 * change while a window is dragged.
 */
private val SIZE_BUCKET = 50.dp

/**
 * Reports what this window's chrome costs the page, for the developer measuring it.
 *
 * Renders nothing. Off unless `BOSS_CHROME_BUDGET=1` or `-Dboss.chrome.budget=1`, so it costs a
 * disabled `LaunchedEffect` and nothing else on a normal launch.
 *
 * This is a log line rather than a status-bar item on purpose: the configuration most worth
 * measuring is the one with the bottom bar switched off, and a readout that lives in the bottom bar
 * cannot report that. A visible readout belongs next to the density control in Settings, which is
 * reachable whatever the chrome is doing - that arrives with the density setting itself.
 *
 * [dimens] is a parameter rather than a `BossChrome.dimens` read inside this function, for the same
 * reason [ChromeMetrics.mainPanelBudget] refuses a default for it. Resolving it here would silently
 * report `Comfortable` if a provider were ever installed *between* this call and the bars, which is
 * the natural place to put one; taking it as an argument makes the caller name it from the same
 * scope that provides it.
 */
@Composable
fun ChromeBudgetReadout(
    windowId: String?,
    appearance: WindowAppearanceSettings,
    focusMode: FocusModeSettings,
    dimens: ChromeDimens,
) {
    if (!readoutEnabled) return

    val budget = ChromeMetrics.mainPanelBudget(appearance, focusMode, dimens)

    // containerSize is the window's content area in px; on macOS that includes the area the traffic
    // lights are drawn over, which is exactly the space the title row is reserving.
    val containerSize = LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val windowHeight = with(density) { containerSize.height.toDp() }
    val windowWidth = with(density) { containerSize.width.toDp() }

    // `windowHeight > 0.dp` is in the key list because bucket 0 covers both the degenerate
    // mid-layout size and any real size under 50dp: without it, a window whose first real size
    // landed in that bucket would never re-key and never log.
    LaunchedEffect(windowId, budget, windowHeight > 0.dp, windowHeight.bucket(), windowWidth.bucket()) {
        if (windowHeight <= 0.dp || windowWidth <= 0.dp) return@LaunchedEffect

        val heightPercent = budget.verticalFractionOf(windowHeight) * 100
        val widthPercent = budget.horizontalFractionOf(windowWidth) * 100
        logger.info(
            LogCategory.UI,
            "chrome budget: page gets ${heightPercent.format1()}% of height, ${widthPercent.format1()}% of width",
            mapOf(
                // Without this, two open windows produce two indistinguishable lines.
                "windowId" to (windowId ?: "unknown"),
                "windowDp" to "${windowWidth.value.toInt()}x${windowHeight.value.toInt()}",
                "chromeVerticalDp" to budget.vertical.value.toInt(),
                "chromeHorizontalDp" to budget.horizontal.value.toInt(),
            ),
        )
    }
}

private fun Dp.bucket(): Int = (value / SIZE_BUCKET.value).toInt()

/**
 * One decimal place, rounded rather than truncated, without pulling in a platform formatter this
 * module would have to expect/actual for.
 */
private fun Float.format1(): String {
    val scaled = (this * 10 + 0.5f).toInt()
    return "${scaled / 10}.${scaled % 10}"
}
