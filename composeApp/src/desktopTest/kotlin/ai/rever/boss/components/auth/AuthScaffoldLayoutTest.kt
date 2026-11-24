package ai.rever.boss.components.auth

import ai.rever.boss.components.auth.forms.AuthScaffold
import ai.rever.boss.components.auth.forms.BrandPanelMinWindowWidth
import ai.rever.boss.components.auth.forms.authBrandSiteOverride
import ai.rever.boss.components.auth.forms.showsBrandPanel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Layout guarantees for [AuthScaffold], the frame every authentication screen sits in.
 *
 * The bug these exist for: the old `AuthCard` declared `.fillMaxWidth().widthIn(max = 400.dp)`, which
 * looks like a 400dp cap and is not one. `fillMaxWidth` measures its child with
 * `minWidth == maxWidth == the parent's width`, and `widthIn` enforces incoming constraints, so the
 * cap was coerced straight back up and the card spanned the whole window. Nothing in review catches
 * that - the intent is written right there in the source - and nothing catches it on a small window
 * either, because at 400dp wide the broken and the fixed layout are identical. It needs a measurement.
 *
 * The window is reproduced with `clipToBounds()`, following [ai.rever.boss.crash.CrashReportDialogLayoutTest]:
 * without clipping, content wider than the window is merely painted outside it and every assertion
 * about visibility still passes.
 *
 * A plain `Text` stands in for a screen's fields, so no `LoginViewModel` - and therefore no auth
 * service, no network - is dragged into a layout test, and no test tag has to be added to production
 * code to make it measurable.
 */
class AuthScaffoldLayoutTest {
    private companion object {
        /** The house card width, and what the form column must measure. */
        val EXPECTED_COLUMN_WIDTH = 400.dp

        /**
         * The "wide window" these tests use, and why it is not something like 1600dp.
         *
         * The desktop test scene is 1024x768, and `Modifier.size` **enforces incoming constraints** -
         * so asking for 1600dp here silently yields 1024dp and every coordinate below shifts by
         * hundreds of dp with nothing to say why. (That is the same coercion rule that made the bug
         * under test: a size request the incoming constraints refuse.) 1000dp fits the scene and is
         * still comfortably past [BrandPanelMinWindowWidth], which is what these tests need.
         */
        val WIDE_WINDOW_WIDTH = 1000.dp

        /**
         * dp-to-px rounding slack on a measured width. Generous enough to survive rounding, far
         * tighter than any real regression: the bug this pins measured the full window width, so a
         * failure is off by hundreds of dp, not by two.
         */
        val WIDTH_TOLERANCE = 2.dp

        const val FIELD = "email field stand-in"

        /**
         * Copy unique to the brand panel, used to detect whether it rendered.
         *
         * Not the wordmark: "BOSS CONSOLE" appears in the narrow layout too, where the form pane
         * carries it instead, so it cannot tell the two layouts apart.
         */
        const val BRAND_HEADLINE = "The governed workspace for AI agents"
    }

    @get:Rule
    val rule = createComposeRule()

    private var savedOverride: Boolean? = null

    /**
     * Turns the brand page off for these tests.
     *
     * It is on by default, and composing it starts a real Chromium view - which in a test scene has no
     * usable AWT window and fails with "Can't obtain the display ID of a closed window". These tests are
     * about the scaffold's geometry, so the browser has no business in them. The override is used rather
     * than the system property because the environment variable takes precedence over the property, and
     * a developer may have it set.
     */
    @Before
    fun disableBrandPage() {
        savedOverride = authBrandSiteOverride
        authBrandSiteOverride = false
    }

    @After
    fun restoreBrandPage() {
        authBrandSiteOverride = savedOverride
    }

    private fun setScaffoldInWindow(
        width: Dp,
        height: Dp,
    ) {
        rule.setContent {
            Box(modifier = Modifier.size(width, height).clipToBounds()) {
                AuthScaffold(title = "Sign in", subtitle = "Continue to BOSS") {
                    // fillMaxWidth so the node measures whatever the column allows - which is the
                    // thing under test. A fixed-width child would pass even with the cap broken.
                    Text(FIELD, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    @Test
    fun `the form column is capped at the house width on a wide window`() {
        setScaffoldInWindow(WIDE_WINDOW_WIDTH, 700.dp)

        val bounds = rule.onNodeWithText(FIELD).getBoundsInRoot()
        val width = bounds.right - bounds.left
        assertTrue(
            abs((width - EXPECTED_COLUMN_WIDTH).value) <= WIDTH_TOLERANCE.value,
            "form column should be $EXPECTED_COLUMN_WIDTH on a $WIDE_WINDOW_WIDTH window, measured " +
                "$width - a wider measurement means a fill modifier is defeating the cap again",
        )
    }

    @Test
    fun `the form sits in the right-hand pane beside the brand panel`() {
        setScaffoldInWindow(WIDE_WINDOW_WIDTH, 700.dp)

        val field = rule.onNodeWithText(FIELD).getBoundsInRoot()
        // Right of centre. Not merely "not at the left edge": the column used to be pinned to the
        // window's left edge by BoxWithConstraints' default TopStart alignment, so capping the width
        // without fixing the alignment would have shipped a 400dp card hugging the left.
        assertTrue(
            field.left > WIDE_WINDOW_WIDTH / 2,
            "form should sit in the right-hand pane of a $WIDE_WINDOW_WIDTH window, " +
                "left edge measured ${field.left}",
        )
        rule.onNodeWithText(BRAND_HEADLINE).assertExists()
    }

    @Test
    fun `a narrow window drops the brand panel and centres the form`() {
        setScaffoldInWindow(700.dp, 600.dp)

        rule.onNodeWithText(BRAND_HEADLINE).assertDoesNotExist()

        val field = rule.onNodeWithText(FIELD).getBoundsInRoot()
        val leftGap = field.left.value
        val rightGap = 700f - field.right.value
        assertTrue(
            abs(leftGap - rightGap) <= WIDTH_TOLERANCE.value,
            "form should be centred on a 700dp window: ${leftGap}dp left vs ${rightGap}dp right",
        )
    }

    @Test
    fun `a window narrower than the cap shrinks instead of overflowing`() {
        // The reason the cap is `widthIn(max=)` and not a fixed `width()`: at 320dp there is not
        // 400dp to be had, and a fixed width would push the field off the edge.
        setScaffoldInWindow(320.dp, 600.dp)

        val field = rule.onNodeWithText(FIELD).getBoundsInRoot()
        assertTrue(field.right.value <= 320f, "form overflowed a 320dp window: right edge ${field.right}")
        assertTrue(field.left.value >= 0f, "form overflowed a 320dp window: left edge ${field.left}")
    }

    @Test
    fun `the breakpoint is decided by window width alone`() {
        // Pure, so the rule is checkable without a display - the same reason shouldRouteHeavyweight
        // is a function in BossDialog.kt.
        assertTrue(showsBrandPanel(BrandPanelMinWindowWidth))
        assertTrue(showsBrandPanel(BrandPanelMinWindowWidth + 1.dp))
        assertFalse(showsBrandPanel(BrandPanelMinWindowWidth - 1.dp))
        assertFalse(showsBrandPanel(0.dp))
    }
}
