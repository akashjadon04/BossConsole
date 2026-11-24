package ai.rever.boss.components.window_panel.components.main_window_panels

import ai.rever.boss.platform.CursorUtil.cursorForHorizontalResize
import ai.rever.boss.window.TabBarVerticalWidthRange
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

/**
 * How wide a band of the bar's trailing edge answers to a resize drag.
 *
 * An OVERLAY on the bar, not a strip beside it. The first version was a 6dp painted strip laid out
 * between the bar and the content, which meant the bar's contents ended 6dp short of the boundary
 * where they used to end 1dp short - a margin down the bar's right edge that nobody asked for.
 *
 * So it takes no layout width at all and sits over the bar's last few dp instead. That band is the
 * horizontal padding every row in the bar already carries, so the overlay lands on space rather
 * than on a tab's close button. It has to be over the BAR rather than over the content, because
 * JxBrowser composites its surface above the Compose scene and a band over a browser pane would
 * never see the pointer.
 */
private val RESIZE_BAND = 5.dp

@Composable
internal fun BoxScope.VerticalTabBarResizeHandle(
    enabled: Boolean,
    currentWidth: Float,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    if (!enabled) return

    // rememberUpdatedState, because the gesture coroutine outlives the composition that started
    // it: a drag begun before a recomposition would otherwise go on reporting to the callbacks
    // captured when it started, and go on measuring from a width that has since moved.
    val latestWidth by rememberUpdatedState(currentWidth)
    val latestPreview by rememberUpdatedState(onPreview)
    val latestCommit by rememberUpdatedState(onCommit)

    Box(
        modifier =
            Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(RESIZE_BAND)
                .cursorForHorizontalResize()
                // pointerInput(Unit), so the gesture is not restarted by the width changing under
                // it - which it does on every frame of the drag this block reports.
                .pointerInput(Unit) {
                    var startWidth = latestWidth
                    var accumulated = 0f
                    detectDragGestures(
                        onDragStart = {
                            startWidth = latestWidth
                            accumulated = 0f
                        },
                        onDragEnd = { latestCommit(clampBarWidth(startWidth + accumulated.toDp().value)) },
                        onDragCancel = { latestCommit(clampBarWidth(startWidth + accumulated.toDp().value)) },
                    ) { change, dragAmount ->
                        change.consume()
                        accumulated += dragAmount.x
                        latestPreview(clampBarWidth(startWidth + accumulated.toDp().value))
                    }
                },
    )
}

/** A width the bar can actually be. The one place the range is applied to a dragged value. */
internal fun clampBarWidth(dp: Float): Float = dp.coerceIn(TabBarVerticalWidthRange)
