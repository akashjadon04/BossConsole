package ai.rever.boss.app

import ai.rever.boss.focusmode.FocusModeEdge
import ai.rever.boss.focusmode.FocusModeSettings
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.delay

/**
 * Hover-reveal state for one window edge.
 *
 * Three layers, in order:
 * - [hoveringStrip]: raw cursor presence in the invisible edge strip
 * - [hoverRevealed]: set after the reveal delay threshold is met
 * - [shown]: debounced visibility with a grace period, so the bar doesn't flicker
 *   while the mouse travels from the strip onto the revealed content
 */
internal class FocusModeEdgeRevealState {
    var hoveringStrip by mutableStateOf(false)
    var hoverRevealed by mutableStateOf(false)
    var shown by mutableStateOf(false)

    /** Hover on the revealed bar itself, which holds it open. */
    val interactionSource = MutableInteractionSource()
}

/**
 * Focus-mode hover-reveal state for the four window edges.
 *
 * The named `show*` / `*InteractionSource` accessors are what the scaffold and the
 * menu actions bind to; [get] is the per-edge view the effects and strips use.
 */
internal class FocusModeRevealState {
    private val edges = FocusModeEdge.entries.associateWith { FocusModeEdgeRevealState() }

    operator fun get(edge: FocusModeEdge): FocusModeEdgeRevealState = edges.getValue(edge)

    var showTopBar: Boolean
        get() = this[FocusModeEdge.TOP].shown
        set(value) {
            this[FocusModeEdge.TOP].shown = value
        }

    var showLeftSidebar: Boolean
        get() = this[FocusModeEdge.LEFT].shown
        set(value) {
            this[FocusModeEdge.LEFT].shown = value
        }

    var showRightSidebar: Boolean
        get() = this[FocusModeEdge.RIGHT].shown
        set(value) {
            this[FocusModeEdge.RIGHT].shown = value
        }

    var showBottomBar: Boolean
        get() = this[FocusModeEdge.BOTTOM].shown
        set(value) {
            this[FocusModeEdge.BOTTOM].shown = value
        }

    val topBarInteractionSource get() = this[FocusModeEdge.TOP].interactionSource
    val leftSidebarInteractionSource get() = this[FocusModeEdge.LEFT].interactionSource
    val rightSidebarInteractionSource get() = this[FocusModeEdge.RIGHT].interactionSource
    val bottomBarInteractionSource get() = this[FocusModeEdge.BOTTOM].interactionSource
}

/**
 * Creates the reveal state and runs its per-edge delay and grace-period effects.
 *
 * Each edge is gated on [FocusModeSettings.hides] rather than on focus mode as a
 * whole: an edge focus mode is not set to clear is simply shown, exactly as when
 * focus mode is off. That is what keeps the sidebars up on Windows, where
 * hover-reveal cannot fire.
 */
@Composable
internal fun rememberFocusModeReveal(settings: FocusModeSettings): FocusModeRevealState {
    val state = remember { FocusModeRevealState() }
    FocusModeEdge.entries.forEach { edge ->
        EdgeRevealEffects(
            edge = state[edge],
            hidden = settings.hides(edge),
            revealDelayMs = settings.revealDelayMs,
        )
    }
    return state
}

/**
 * The delay-then-reveal and grace-period-then-hide effects for a single edge.
 *
 * [hidden] is the whole gate: an edge focus mode does not clear is simply shown and
 * never runs a timer.
 */
@Composable
private fun EdgeRevealEffects(
    edge: FocusModeEdgeRevealState,
    hidden: Boolean,
    revealDelayMs: Long,
) {
    // Hover on the revealed bar itself holds it open while the pointer is on it.
    val barHovered by edge.interactionSource.collectIsHoveredAsState()

    // Apply reveal delay before triggering reveal
    LaunchedEffect(edge.hoveringStrip, revealDelayMs) {
        if (edge.hoveringStrip) {
            delay(revealDelayMs)
            edge.hoverRevealed = true
        } else {
            edge.hoverRevealed = false
        }
    }

    // Add grace period before hiding to prevent flicker when moving mouse from strip to sidebar
    LaunchedEffect(edge.hoverRevealed, barHovered, hidden) {
        if (!hidden || edge.hoverRevealed || barHovered) {
            edge.shown = true
        } else {
            // Hold the bar briefly so an open menu is not yanked away
            delay(HIDE_GRACE_PERIOD_MS)
            if (!edge.hoverRevealed && !barHovered) {
                edge.shown = false
            }
        }
    }
}

/**
 * Hover reveal strips for focus mode — dynamic sizing to avoid blocking clicks.
 * Each strip uses revealOffset when hidden, 1dp when visible (doesn't block clicks).
 *
 * A strip is laid out only for an edge focus mode actually clears. An edge that
 * stays visible has nothing to reveal, and its strip would otherwise be an
 * invisible [revealOffsetDp]-wide band sitting over the edge of live content.
 *
 * [barVisible] applies the same rule to the other way a bar can be absent. A bar switched off in
 * `WindowAppearanceSettings` is not coming back on hover - the scaffold requires that flag AND the
 * reveal flag to agree - so a strip there is a dead band over live content, exactly what the
 * paragraph above rules out. Sweeping such an edge would also flip `shown` to true and, for the top
 * bar, take the quick-actions cluster away for the hover plus the grace period without putting the
 * bar back in its place.
 */
@Composable
internal fun BoxScope.FocusModeHoverStrips(
    state: FocusModeRevealState,
    settings: FocusModeSettings,
    revealOffsetDp: Dp,
    barVisible: (FocusModeEdge) -> Boolean,
) {
    if (!settings.autoRevealEnabled) return

    FocusModeEdge.entries.forEach { edge ->
        if (settings.hides(edge) && barVisible(edge)) {
            val edgeState = state[edge]
            val thickness = if (edgeState.shown) 1.dp else revealOffsetDp
            HoverStrip(
                edge = edge,
                modifier =
                    when (edge) {
                        FocusModeEdge.TOP -> Modifier.fillMaxWidth().height(thickness).align(Alignment.TopStart)
                        FocusModeEdge.BOTTOM -> Modifier.fillMaxWidth().height(thickness).align(Alignment.BottomStart)
                        FocusModeEdge.LEFT -> Modifier.fillMaxHeight().width(thickness).align(Alignment.CenterStart)
                        FocusModeEdge.RIGHT -> Modifier.fillMaxHeight().width(thickness).align(Alignment.CenterEnd)
                    },
                onHoverChange = { edgeState.hoveringStrip = it },
            )
        }
    }
}

/**
 * Test tag of [edge]'s hover strip. The strips are invisible and hold no content,
 * so a tag is the only way a test can tell one is there - see `FocusModeRevealTest`.
 */
internal fun focusStripTag(edge: FocusModeEdge) = "focus-strip-${edge.name.lowercase()}"

/** One transparent edge band that reports the pointer entering and leaving it. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HoverStrip(
    edge: FocusModeEdge,
    modifier: Modifier,
    onHoverChange: (Boolean) -> Unit,
) {
    Box(
        modifier =
            modifier
                .zIndex(10f)
                .testTag(focusStripTag(edge))
                .background(Color.Transparent)
                .onPointerEvent(PointerEventType.Enter) { onHoverChange(true) }
                .onPointerEvent(PointerEventType.Exit) { onHoverChange(false) },
    )
}

/** How long a bar stays up after the pointer leaves, so menu interactions survive. */
private const val HIDE_GRACE_PERIOD_MS = 2000L
