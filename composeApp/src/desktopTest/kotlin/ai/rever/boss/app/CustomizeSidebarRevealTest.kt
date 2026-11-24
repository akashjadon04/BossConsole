package ai.rever.boss.app

import ai.rever.boss.window.WindowAppearanceSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [withCustomizeTargetRevealed], the second half of the "View - Customize Sidebar..." repair.
 *
 * The first half - `reveal.showLeftSidebar = true` - predates this feature and was enough while
 * focus mode was the only thing that could hide a strip. It is not any more: the scaffold now
 * requires the preference AND the reveal flag to agree, so a strip switched off in settings would
 * swallow the menu item exactly the way focus mode used to, and the existing force-reveal would
 * quietly do nothing. That regression is invisible to every other test here, and the effect it
 * lives in cannot be exercised directly, which is why the decision is a pure function.
 */
class CustomizeSidebarRevealTest {
    @Test
    fun `reveals the left strip when the customize button is on the left`() {
        val hidden = WindowAppearanceSettings(showLeftStrip = false)

        assertTrue(withCustomizeTargetRevealed(hidden, onLeft = true).showLeftStrip)
    }

    @Test
    fun `reveals the right strip when the customize button is on the right`() {
        val hidden = WindowAppearanceSettings(showRightStrip = false)

        assertTrue(withCustomizeTargetRevealed(hidden, onLeft = false).showRightStrip)
    }

    @Test
    fun `does not disturb the strip the button is not on`() {
        // Asking to customise the left sidebar is not a reason to hand back a right strip the user
        // switched off on purpose.
        val bothHidden = WindowAppearanceSettings(showLeftStrip = false, showRightStrip = false)

        val afterLeft = withCustomizeTargetRevealed(bothHidden, onLeft = true)
        assertTrue(afterLeft.showLeftStrip)
        assertEquals(false, afterLeft.showRightStrip)

        val afterRight = withCustomizeTargetRevealed(bothHidden, onLeft = false)
        assertTrue(afterRight.showRightStrip)
        assertEquals(false, afterRight.showLeftStrip)
    }

    @Test
    fun `is a no-op when the strip is already showing`() {
        // The effect compares before writing, so returning an equal object is what keeps
        // "Customize Sidebar..." from writing the settings file on every invocation.
        //
        // Both strips named explicitly: this test is about the no-op, not about what the shipped
        // defaults are, and they now start hidden - which is the case the test ABOVE covers.
        val visible = WindowAppearanceSettings(showLeftStrip = true, showRightStrip = true)

        assertEquals(visible, withCustomizeTargetRevealed(visible, onLeft = true))
        assertEquals(visible, withCustomizeTargetRevealed(visible, onLeft = false))
    }

    @Test
    fun `touches nothing else in the settings`() {
        // It writes the whole object back, so a stray copy() would silently revert an unrelated
        // preference - the title bar and the other two bars included.
        val varied =
            WindowAppearanceSettings(
                showTitleBar = false,
                showTopBar = false,
                showBottomBar = false,
                showLeftStrip = false,
            )

        val revealed = withCustomizeTargetRevealed(varied, onLeft = true)

        assertEquals(varied.copy(showLeftStrip = true), revealed)
    }
}
