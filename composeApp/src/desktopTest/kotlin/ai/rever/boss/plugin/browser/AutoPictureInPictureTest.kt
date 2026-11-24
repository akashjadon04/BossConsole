package ai.rever.boss.plugin.browser

import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When a backgrounded tab pops its video out, and what counts as "still in a call".
 *
 * The counting half is the part that looks like bookkeeping and is not: a call opens audio and
 * video separately, so the obvious boolean reports the call over the moment anyone mutes.
 */
class AutoPictureInPictureTest {
    // ---- the decision ----

    @Test
    fun `a tab holding the camera or mic pops out when it is backgrounded`() {
        assertTrue(
            shouldAutoPictureInPicture(
                url = "https://meet.google.com/abc-defg-hij",
                isCapturing = true,
                alreadyPoppedOut = false,
                enabled = true,
            ),
        )
    }

    @Test
    fun `a tab capturing nothing is left alone`() {
        // The whole point of gating on capture: a YouTube tab must not follow you around.
        assertFalse(
            shouldAutoPictureInPicture(
                url = "https://www.youtube.com/watch?v=abc",
                isCapturing = false,
                alreadyPoppedOut = false,
                enabled = true,
            ),
        )
    }

    @Test
    fun `a pop-out already on screen is never replaced`() {
        // Chromium allows one at a time, and one the user opened by hand is theirs.
        assertFalse(
            shouldAutoPictureInPicture(
                url = "https://meet.google.com/abc-defg-hij",
                isCapturing = true,
                alreadyPoppedOut = true,
                enabled = true,
            ),
        )
    }

    @Test
    fun `only https pages qualify`() {
        for (url in listOf("http://meet.example.com/x", "file:///tmp/call.html", "about:blank", "")) {
            assertFalse(
                shouldAutoPictureInPicture(url, isCapturing = true, alreadyPoppedOut = false, enabled = true),
                "$url should not qualify",
            )
        }
        assertTrue(
            shouldAutoPictureInPicture(
                "HTTPS://meet.example.com/x",
                isCapturing = true,
                alreadyPoppedOut = false,
                enabled = true,
            ),
            "the scheme comparison must be case-insensitive",
        )
    }

    // ---- the counting ----

    @Test
    fun `muting the mic mid-call does not end the call`() {
        // The regression this exists for. Audio and video are separate streams; a boolean would
        // report "no longer capturing" here while the camera is still on.
        val tracker = CaptureTracker()
        tracker.started(CapturedMedia.AUDIO)
        tracker.started(CapturedMedia.VIDEO)

        tracker.stopped(CapturedMedia.AUDIO)

        assertTrue(tracker.isCapturing(), "video is still live, so the call is still running")
    }

    @Test
    fun `the call ends only when every stream has stopped`() {
        val tracker = CaptureTracker()
        tracker.started(CapturedMedia.AUDIO)
        tracker.started(CapturedMedia.VIDEO)
        tracker.stopped(CapturedMedia.AUDIO)
        tracker.stopped(CapturedMedia.VIDEO)

        assertFalse(tracker.isCapturing())
    }

    @Test
    fun `two streams of one kind need two stops`() {
        val tracker = CaptureTracker()
        tracker.started(CapturedMedia.VIDEO)
        tracker.started(CapturedMedia.VIDEO)

        tracker.stopped(CapturedMedia.VIDEO)
        assertTrue(tracker.isCapturing(), "one camera stream is still open")

        tracker.stopped(CapturedMedia.VIDEO)
        assertFalse(tracker.isCapturing())
    }

    @Test
    fun `a stop with no matching start cannot drive the count negative`() {
        // A handle created while a call is already running never saw the start - JxBrowser does
        // not replay events - so the first thing it sees can be a stop. If that went to -1, the
        // next start would leave the count at 0 and the tab would look idle mid-call.
        val tracker = CaptureTracker()
        tracker.stopped(CapturedMedia.AUDIO)
        tracker.stopped(CapturedMedia.AUDIO)

        tracker.started(CapturedMedia.AUDIO)

        assertTrue(tracker.isCapturing())
    }

    @Test
    fun `a fresh tracker is not capturing`() {
        assertFalse(CaptureTracker().isCapturing())
    }

    @Test
    fun `clear drops everything`() {
        val tracker = CaptureTracker()
        tracker.started(CapturedMedia.AUDIO)
        tracker.started(CapturedMedia.VIDEO)

        tracker.clear()

        assertFalse(tracker.isCapturing())
    }

    // region resize grip geometry

    @Test
    fun `each zone of the grip strip answers for itself`() {
        // The shipped bug this pins: the whole strip carried bottom-right semantics, so the
        // bottom-LEFT corner promised a resize that grew the window away from the pointer.
        assertEquals(GripZone.LEFT, gripZoneAt(x = 0, width = 400))
        assertEquals(GripZone.LEFT, gripZoneAt(x = GRIP_CORNER_WIDTH, width = 400))
        assertEquals(GripZone.BOTTOM, gripZoneAt(x = 200, width = 400))
        assertEquals(GripZone.RIGHT, gripZoneAt(x = 400 - GRIP_CORNER_WIDTH, width = 400))
        assertEquals(GripZone.RIGHT, gripZoneAt(x = 400, width = 400))
    }

    @Test
    fun `a strip narrower than two corners still resolves`() {
        // Left is tested first, so a strip too narrow for both corners reads as LEFT rather
        // than falling through to an unreachable BOTTOM.
        assertEquals(GripZone.LEFT, gripZoneAt(x = 10, width = 30))
    }

    @Test
    fun `the right corner grows right and down, leaving the origin alone`() {
        val from = Rectangle(100, 50, 400, 300)

        val to = resizedPopOutBounds(from, GripZone.RIGHT, dx = 60, dy = 40)

        assertEquals(Rectangle(100, 50, 460, 340), to)
    }

    @Test
    fun `the bottom edge changes height only`() {
        val from = Rectangle(100, 50, 400, 300)

        val to = resizedPopOutBounds(from, GripZone.BOTTOM, dx = 90, dy = 25)

        assertEquals(Rectangle(100, 50, 400, 325), to)
    }

    @Test
    fun `the left corner keeps the right edge still`() {
        val from = Rectangle(100, 50, 400, 300)

        val to = resizedPopOutBounds(from, GripZone.LEFT, dx = 60, dy = 10)

        assertEquals(340, to.width)
        assertEquals(160, to.x)
        // The whole point: x moved by exactly what the width lost.
        assertEquals(from.x + from.width, to.x + to.width, "the right edge moved")
    }

    @Test
    fun `a left drag past the minimum stops instead of walking the window sideways`() {
        // The classic bug in a hand-rolled left-edge resize: clamping the width while still
        // moving x drags the window across the screen once it can shrink no further.
        val from = Rectangle(100, 50, 400, 300)

        val to = resizedPopOutBounds(from, GripZone.LEFT, dx = 9_000, dy = 0)

        assertEquals(MIN_POP_OUT_EDGE, to.width)
        assertEquals(from.x + from.width, to.x + to.width, "the right edge moved")
    }

    @Test
    fun `every zone clamps at the minimum edge`() {
        val from = Rectangle(0, 0, 400, 300)

        for (zone in GripZone.entries) {
            val to = resizedPopOutBounds(from, zone, dx = -9_000, dy = -9_000)
            assertTrue(to.width >= MIN_POP_OUT_EDGE, "$zone let width fall below the minimum")
            assertTrue(to.height >= MIN_POP_OUT_EDGE, "$zone let height fall below the minimum")
        }
    }

    // endregion

    @Test
    fun `the setting switches the whole feature off`() {
        // Every other input says "pop out" - capturing, https, nothing open yet. Only the setting
        // refuses, and it refuses regardless of the rest: "never", where the others say "not now".
        assertFalse(
            shouldAutoPictureInPicture(
                url = "https://meet.google.com/abc",
                isCapturing = true,
                alreadyPoppedOut = false,
                enabled = false,
            ),
        )
    }
}
