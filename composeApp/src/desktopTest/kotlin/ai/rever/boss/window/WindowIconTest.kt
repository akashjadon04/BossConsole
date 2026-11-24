package ai.rever.boss.window

import ai.rever.boss.testsupport.repoRoot
import java.awt.image.BufferedImage
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins that the icon loads, that the list is the shape Windows wants, and that the net's decision is
 * the one it claims.
 *
 * [BossWindowIcon] degrades to an empty list rather than throwing when `/boss_icon.png` cannot be
 * read, because [ai.rever.boss.crash.CrashHandler] is one of its callers and a missing icon must not
 * be the reason a crash report goes unshown. That fallback is also how this whole feature could
 * become a silent no-op: every window would carry on showing the Java icon and nothing would say so.
 * Hence a test on the resource, not just on the call sites.
 */
class WindowIconTest {
    @Test
    fun `the window icon resource is on the classpath`() {
        assertTrue(
            BossWindowIcon.images.isNotEmpty(),
            "/boss_icon.png did not load - every window would silently fall back to the Java icon",
        )
    }

    @Test
    fun `the icon list is square, distinct and covers the sizes Windows asks for`() {
        val sizes = BossWindowIcon.images.map { it.width }
        // Asserted rather than assumed: on an empty list the checks below would throw
        // NoSuchElementException instead of failing with something a reader can act on.
        assertTrue(sizes.isNotEmpty(), "no icon variants at all - see the resource test above")

        BossWindowIcon.images.forEach { image ->
            assertEquals(image.width, image.height, "icon variants must be square")
        }
        assertEquals(sizes, sizes.distinct(), "duplicate icon sizes waste memory and help nothing")
        // 16 for the title bar and 32 for the taskbar button and Alt-Tab card are the two Windows
        // actually asks for; the source is kept so a high-DPI request has something to work from.
        assertTrue(16 in sizes, "no 16px variant, which is what the Windows title bar asks for")
        assertTrue(32 in sizes, "no 32px variant, which is what the taskbar and Alt-Tab ask for")
        assertTrue(sizes.max() >= 256, "the full-size source should be kept for high-DPI requests")
    }

    @Test
    fun `the small variants are not blank`() {
        // A progressive downscale that got its loop bounds wrong would still produce correctly sized
        // images, just empty ones - and nothing else here would notice.
        val small = BossWindowIcon.images.first { it.width == 16 }
        val opaquePixels =
            (0 until small.width).sumOf { x ->
                (0 until small.height).count { y -> (small.getRGB(x, y) ushr 24) != 0 }
            }
        assertTrue(opaquePixels > 0, "the 16px variant is fully transparent")
    }

    @Test
    fun `the compose painter resolves from the same decode`() {
        assertTrue(
            BossWindowIcon.painter != null,
            "BossWindowIcon.painter is null, so every Compose window would go unbranded",
        )
    }

    @Test
    fun `the net brands a frame carrying no icon`() {
        assertEquals(
            BossWindowIcon.images.size,
            DefaultWindowIcon.iconsToApply(emptyList())?.size,
            "a frame with no icon should be given the full list",
        )
    }

    @Test
    fun `the net leaves an already-branded frame alone`() {
        // The whole point of the emptiness check: a window that deliberately carries a different
        // icon - including every window in this repo, which sets its own before this ever runs -
        // must not be overwritten.
        val existing = BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB)
        assertEquals(
            null,
            DefaultWindowIcon.iconsToApply(listOf(existing)),
            "an existing icon must not be replaced",
        )
    }

    /**
     * The two copies of the art are byte-identical today, and this keeps them that way.
     *
     * `desktopMain/resources` is what every window title bar now reads; `commonMain/composeResources`
     * is what `AuthFormComponents`, `LoadingScreen` and `OfflineScreen` render. The duplication
     * predates this feature, but this feature makes it load-bearing in a second place: a designer
     * updating only the Compose resource would leave every window on the old art, with nothing
     * failing to say so.
     */
    @Test
    fun `both copies of the icon art are identical`() {
        val root = repoRoot()
        val windowCopy = File(root, "composeApp/src/desktopMain/resources/boss_icon.png")
        val uiCopy = File(root, "composeApp/src/commonMain/composeResources/drawable/boss_icon.png")
        check(windowCopy.isFile) { "missing ${windowCopy.absolutePath}" }
        check(uiCopy.isFile) { "missing ${uiCopy.absolutePath}" }

        assertTrue(
            windowCopy.readBytes().contentEquals(uiCopy.readBytes()),
            "boss_icon.png differs between desktopMain/resources (window icons) and " +
                "commonMain/composeResources (in-app logos). Update both, or the title bars and the " +
                "in-app logo will show different art.",
        )
    }
}
