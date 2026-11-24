package ai.rever.boss.plugin.browser

/**
 * The kind of device capture a page has open. Deliberately not JxBrowser's `MediaStreamType`:
 * keeping this file free of engine types is what lets the decision below be unit-tested, since
 * nothing here can construct a `Browser`.
 */
internal enum class CapturedMedia {
    AUDIO,
    VIDEO,
}

/**
 * What a page is capturing right now, counted per kind rather than flagged.
 *
 * Counting is the whole point. A video call opens audio and video as separate streams and closes
 * them separately - muting the mic mid-call ends the audio stream on its own - so a boolean flips
 * to "not in a call" on the first stop while the call is plainly still running. It also has to
 * survive the same kind twice: a page that adds a second camera, or re-negotiates its stream,
 * raises two starts and the first stop must not cancel both.
 *
 * Synchronised because the capture events arrive on a JxBrowser thread while the tab-switch path
 * reads this from the UI thread.
 */
internal class CaptureTracker {
    private val counts = mutableMapOf<CapturedMedia, Int>()

    @Synchronized
    fun started(media: CapturedMedia) {
        counts[media] = (counts[media] ?: 0) + 1
    }

    @Synchronized
    fun stopped(media: CapturedMedia) {
        val remaining = (counts[media] ?: 0) - 1
        // Never let a count go negative: a stop with no matching start would otherwise need two
        // starts to climb back to capturing. JxBrowser replays no events to a late subscriber, so
        // a handle created while a call is already running sees exactly that.
        if (remaining <= 0) counts.remove(media) else counts[media] = remaining
    }

    @Synchronized
    fun isCapturing(): Boolean = counts.isNotEmpty()

    @Synchronized
    fun clear() = counts.clear()
}

/**
 * Whether backgrounding this tab should pop its video out.
 *
 * Mirrors the video-conferencing branch of Chrome's `AutoPictureInPictureTabHelper`: a page that
 * holds the camera or microphone is in a call, and a call is worth keeping on screen. Chrome's
 * other branch - ordinary media playback, gated on the Media Engagement Index - is deliberately
 * not copied, so a YouTube tab does not follow you around.
 *
 * `https` only, matching Chrome (which has a browsertest named `CannotAutopipViaHttp`). Capture
 * needs a secure context anyway, so in practice this only excludes `file:` and oddities.
 *
 * @param alreadyPoppedOut a pop-out the user opened by hand must never be replaced, and Chromium
 *   allows only one at a time regardless.
 */
internal fun shouldAutoPictureInPicture(
    url: String,
    isCapturing: Boolean,
    alreadyPoppedOut: Boolean,
    enabled: Boolean,
): Boolean {
    // `enabled` is the user saying "never", where the other two say "not now" - taken as a
    // parameter rather than read from the settings object so this function stays pure, which is
    // the only reason the rule is testable at all.
    if (!enabled || !isCapturing || alreadyPoppedOut) return false
    return url.substringBefore(':', missingDelimiterValue = "").lowercase() == "https"
}

/** Which part of the grip strip a pointer is over: the two corners resize, the rest is the edge. */
internal enum class GripZone { LEFT, BOTTOM, RIGHT }

internal fun gripZoneAt(
    x: Int,
    width: Int,
): GripZone =
    when {
        x <= GRIP_CORNER_WIDTH -> GripZone.LEFT
        x >= width - GRIP_CORNER_WIDTH -> GripZone.RIGHT
        else -> GripZone.BOTTOM
    }

internal fun gripCursorFor(zone: GripZone): java.awt.Cursor =
    java.awt.Cursor.getPredefinedCursor(
        when (zone) {
            GripZone.LEFT -> java.awt.Cursor.SW_RESIZE_CURSOR
            GripZone.RIGHT -> java.awt.Cursor.SE_RESIZE_CURSOR
            GripZone.BOTTOM -> java.awt.Cursor.S_RESIZE_CURSOR
        },
    )

/**
 * The frame's new bounds for a drag of [dx], [dy] from [from] in [zone].
 *
 * The left corner keeps the RIGHT edge still: the window's x moves by exactly what the width
 * loses, so a drag past the minimum stops growing instead of walking the window sideways.
 */
internal fun resizedPopOutBounds(
    from: java.awt.Rectangle,
    zone: GripZone,
    dx: Int,
    dy: Int,
): java.awt.Rectangle {
    val height = (from.height + dy).coerceAtLeast(MIN_POP_OUT_EDGE)
    return when (zone) {
        GripZone.RIGHT -> {
            java.awt.Rectangle(from.x, from.y, (from.width + dx).coerceAtLeast(MIN_POP_OUT_EDGE), height)
        }

        GripZone.LEFT -> {
            val width = (from.width - dx).coerceAtLeast(MIN_POP_OUT_EDGE)
            java.awt.Rectangle(from.x + (from.width - width), from.y, width, height)
        }

        GripZone.BOTTOM -> {
            java.awt.Rectangle(from.x, from.y, from.width, height)
        }
    }
}

/** How wide each corner zone of a pop-out's resize strip is. */
internal const val GRIP_CORNER_WIDTH = 28

/** The smallest a pop-out may be resized to, on either edge. */
internal const val MIN_POP_OUT_EDGE = 120
