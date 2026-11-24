package ai.rever.boss.components.dialogs

import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.LastBaseline
import androidx.compose.ui.layout.LayoutModifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp

/**
 * Sizing fence for scrollable content inside the desktop material `AlertDialog`.
 *
 * Two leaks have to be plugged, or the dialog resizes and jumps while its content is
 * scrolled:
 *
 * 1. Intrinsics: the dialog sizes its popup from intrinsic measurements, and
 *    `heightIn(max)` clamps layout only - so both layout and intrinsic height are
 *    capped here.
 * 2. Baselines: `AlertDialogBaselineLayout` places the text slot at
 *    `titleBaseline + offset - slotFirstBaseline`. `FirstBaseline` merges across
 *    children with MIN policy and propagates out of scroll containers offset by the
 *    scroll position, so scrolling makes the slot's merged `FirstBaseline`
 *    increasingly negative, which pushes the slot down and grows the dialog by
 *    exactly the scrolled amount. Pinning both baselines to constants stops any
 *    scroll-dependent value from escaping. Do not pin to `AlignmentLine.Unspecified`
 *    from a nested layout - it min-merges as a large negative rather than reading as
 *    absent.
 *
 * Apply it *outside* the scroll modifier: `Modifier.dialogScrollFence(220.dp).verticalScroll(…)`.
 *
 * Shared rather than copied because it is not obvious enough to rediscover: it was
 * written once for the update dialog's release notes and the second scrollable dialog
 * would otherwise have shipped with the same jump.
 */
fun Modifier.dialogScrollFence(max: Dp): Modifier = this.then(DialogScrollFence(max))

private data class DialogScrollFence(
    private val max: Dp,
) : LayoutModifier {
    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints,
    ): MeasureResult {
        val cappedMax = max.roundToPx().coerceAtMost(constraints.maxHeight)
        val placeable =
            measurable.measure(
                constraints.copy(
                    minHeight = constraints.minHeight.coerceAtMost(cappedMax),
                    maxHeight = cappedMax,
                ),
            )
        return layout(
            placeable.width,
            placeable.height,
            alignmentLines = mapOf(FirstBaseline to 0, LastBaseline to placeable.height),
        ) {
            placeable.placeRelative(0, 0)
        }
    }

    override fun IntrinsicMeasureScope.minIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int = measurable.minIntrinsicHeight(width).coerceAtMost(max.roundToPx())

    override fun IntrinsicMeasureScope.maxIntrinsicHeight(
        measurable: IntrinsicMeasurable,
        width: Int,
    ): Int = measurable.maxIntrinsicHeight(width).coerceAtMost(max.roundToPx())
}
