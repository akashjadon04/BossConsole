package ai.rever.boss.components.overlays

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import ai.rever.boss.utils.logging.LogLevel
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import java.awt.Window

// Diagnosis and repair for the transparency of a heavyweight overlay window. Its own file rather
// than a section of OverlayWindowBounds.kt, so the instrumentation half stays cheap to delete or
// downgrade independently of the repair.

private val logger = BossLogger.forComponent("OverlayTransparency")

/**
 * Re-assert that an overlay window is actually translucent, and REPORT what every layer that
 * decides that is actually set to, because `transparent = true` intermittently does not take on
 * macOS.
 *
 * Observed on a live macOS HARDWARE build: opening the New Tab dialog produced a flat mid-grey
 * over the whole window instead of the app dimmed behind the dialog. The dialog itself drew
 * correctly, which is what identifies the layer at fault — the backdrop, not the content. The same
 * failure was later reported on the URL-bar suggestion list, on toasts and on the Ctrl+Tab
 * switcher, and NOT on every open — so it is per-window and intermittent, not a property of any one
 * caller.
 *
 * The colour is the evidence, and it has since been MEASURED rather than eyeballed: the reported
 * backdrop is exactly #999999 at every point of the window. The dialog scrim is
 * `Color.Black.copy(alpha = 0.4f)`, and 0.6 x 255 = 153 = #99 — so the scrim composited over an
 * opaque pure WHITE surface. Not the app beneath (that is dark, and would show structure), and not
 * AWT's default #ECECEC (which gives #8D). The overlay window's own surface is being cleared to
 * opaque white.
 *
 * ## The check below cannot fire on macOS, which is why its silence proved nothing
 *
 * `window.background` is not the layer at fault. Compose applies `transparent = true` through
 * skiko's `transparentWindowBackgroundHack(renderApi)`, which returns `Color(0,0,0,0)` for every
 * host EXCEPT Windows-with-Direct3D — so on macOS that alpha is always 0 and the branch below is
 * unreachable. The earlier version of this KDoc offered "the absence of the warning is itself the
 * signal that the diagnosis was wrong" as its falsification test; that test has now been met, on a
 * build that contained this code, in the session that produced the #999999 screenshot.
 *
 * The assignment is a no-op even when reached: `java.awt.Window.setBackground` returns early when
 * `oldBg.equals(bgColor)`, so re-assigning the identical `Color(0,0,0,0)` never reaches
 * `peer.setOpaque(...)`. It is kept only because it costs nothing and still covers the
 * Windows-Direct3D shape, where the hack colour is null and an opaque background IS observable
 * here.
 *
 * ## What actually decides, and what this reports
 *
 * From Compose Multiplatform 1.11.1 and skiko 0.144.6:
 *
 *  1. `SkiaLayer` clears every frame with
 *     `clear(if (transparency && redrawer.isTransparentBackgroundSupported) bg else bg or
 *     0xFF000000)`, and `defaultIsTransparentBackgroundSupported` is unconditionally true on macOS.
 *     So the ONLY way to get an opaque clear there is `transparency == false`.
 *  2. `WindowSkiaLayerComponent.setTransparency(false)` sets the layer background to null, and a
 *     null `JComponent` background inherits an opaque colour from the AWT parent chain. That pair —
 *     `transparency = false` plus an inherited white — is the one combination in this code that
 *     produces exactly the measured `clear(0xFFFFFFFF)`.
 *  3. skiko's macOS Metal path passes `layer.transparency` into the native `createMetalDevice(...)`
 *     ONCE, at device creation, and `MetalDevice` exposes no setter. A window whose Skia layer was
 *     non-transparent when its Metal device was built therefore stays opaque for its whole life,
 *     which is why re-asserting after the fact cannot repair it.
 *
 * Two causes remain and they need opposite fixes — the Java-side flag being wrong (fix: set it
 * before realization) versus everything Java-visible being right and the opacity living below it
 * (fix: recreate the window, or stop relying on per-pixel translucency). [overlayWillPaintOpaque]
 * is the single boolean that separates them, so this samples the state twice per window and logs
 * its own verdict rather than leaving it to be correlated against a screenshot's timestamp.
 *
 * [kind] names the overlay ("modal", "popup", "hud", "ghost", "corner") so a line can be tied to
 * what was on screen; all five kinds share this one code path.
 *
 * DEBUG, not INFO. It ran at INFO for the investigation, where the healthy samples were as
 * load-bearing as the broken ones - only the ratio between them said anything, and the whole
 * diagnosis came from a sample that read healthy on a window that was visibly grey. That question is
 * answered, and three lines per overlay open is not something to ship on by default. Turn it back up
 * with `BOSS_LOG_LEVEL=DEBUG` (or `-Dboss.log.level=DEBUG`) if this recurs; the sampling is kept
 * precisely so a recurrence does not have to be re-instrumented from scratch.
 */
@Composable
internal fun EnsureOverlayWindowTransparent(
    window: Window,
    kind: String,
) {
    // The old `window.background` check that used to lead this function is GONE, not moved. Three
    // things were wrong with it and the third is why it could not simply be left alone:
    //  - it could never fire on macOS, where Compose always supplies Color(0,0,0,0);
    //  - Window.setBackground returns early on an equal colour, so it repaired nothing when it did;
    //  - on Windows with a non-Direct3D render API skiko deliberately leaves the background NULL, so
    //    it fired on every overlay open, logged a WARN, and overwrote that deliberate null - and it
    //    normalised the value the repair below reads, which is what made "inert on Windows" false.
    // SideEffect, not DisposableEffect with an empty onDispose: there is nothing to undo when the
    // window goes away, and an empty onDispose invites a reader to wonder what is missing.
    SideEffect {
        // `displayable`/`showing` here report whether this effect runs BEFORE the window is
        // realized, which is what decides whether anything set from composition could beat the
        // native window's creation.
        logOverlayTransparency(window, kind, phase = "preShow")
    }
    // The second sample has to come from a window listener rather than another effect. Compose
    // effects are driven by composition, which is finished long before AWT gets round to opening
    // the window, so a reading taken here would describe the same pre-realization state twice. The
    // invokeLater behind it defers one more EDT turn, past the first paint, because the Metal
    // device is created lazily on the first render rather than at windowOpened.
    DisposableEffect(window, kind) {
        val listener =
            object : java.awt.event.WindowAdapter() {
                // Re-activation, not just opening. The mechanism argued for below is AppKit
                // resetting the native flag when the application comes back to the front, and an
                // overlay that was ALREADY open across that transition never sees another
                // windowOpened - the New Tab dialog can sit open indefinitely, and a toast lingers
                // for seconds. Repairing only at open would cover half of the reported repro.
                override fun windowActivated(e: java.awt.event.WindowEvent?) = reassertNativeTransparency(window)

                override fun windowDeiconified(e: java.awt.event.WindowEvent?) = reassertNativeTransparency(window)

                override fun windowOpened(e: java.awt.event.WindowEvent?) {
                    logOverlayTransparency(window, kind, phase = "opened")
                    reassertNativeTransparency(window)
                    javax.swing.SwingUtilities.invokeLater {
                        // Again one turn later. The first call happens while the window is still
                        // being ordered on screen, and it is that ordering - always-on-top level and
                        // style bits - that is suspected of resetting the native flag in the first
                        // place, so a single early call can be undone by the very thing it is
                        // fighting.
                        if (window.isDisplayable) reassertNativeTransparency(window)
                        // A deferred sample can land after the overlay is gone - a context menu
                        // dismissed within one EDT turn of opening does exactly that. A disposed
                        // window has no component tree, so the probe finds no layer and the
                        // "unknown reads as opaque" rule reports willPaintOpaque=true. That is the
                        // right answer to the wrong question, and it is indistinguishable in the log
                        // from the failure being hunted, so it must not be logged at all.
                        if (window.isDisplayable) {
                            logOverlayTransparency(window, kind, phase = "postShow")
                        }
                    }
                }
            }
        window.addWindowListener(listener)
        // The repair is otherwise reachable only through windowOpened, so a call site that ever
        // composes after realization would attach past the event and repair nothing, silently. That
        // ordering is exactly what the preShow sample exists to establish and is not yet known, and
        // a fix should not rest on an unknown.
        if (window.isShowing) reassertNativeTransparency(window)
        onDispose { window.removeWindowListener(listener) }
    }
}

/**
 * Force AWT to hand the native window its "not opaque" flag again, after it is on screen.
 *
 * ## Why anything is needed when every Java reading is already correct
 *
 * Measured on a window that was visibly grey at the moment of the sample: `transparency=true`,
 * layer background `#000000 alpha=0`, `renderApi=METAL`, every Swing ancestor `opaque=false`, window
 * background `#000000 alpha=0`, `translucencyCapable=true`. The Skia surface is being cleared to
 * fully transparent pixels and nothing in Swing paints a fill over it, yet the window displays
 * opaque white. So the opacity is BELOW Java - in the native window - and no amount of setting the
 * Java-side flags differently, or earlier, can address it. That is what killed the other candidate
 * fix.
 *
 * The reproduction is the other half of the argument: background the application, bring it back,
 * and from then on almost every overlay is affected. That is a persistent app-level condition
 * rather than a per-window race, and re-activation is exactly when AppKit re-applies a window's
 * level and style bits - which is what resets `NSWindow.isOpaque` behind AWT's back. Every overlay
 * here is `alwaysOnTop`, so every overlay gets that treatment.
 *
 * ## Why the assignment has to go through an OPAQUE colour
 *
 * Both layers between here and the native call short-circuit on the value already being right, and
 * it already IS right on the Java side - which is precisely the problem:
 *
 *  - `java.awt.Window.setBackground` returns early when `oldBg.equals(bgColor)`, so re-assigning
 *    `Color(0,0,0,0)` never reaches the peer at all. This is why the check this file used to carry
 *    could not have repaired anything even had it fired.
 *  - `LWWindowPeer.setOpaque` is guarded by `if (this.isOpaque != isOpaque)`, so even reaching it
 *    with `false` does nothing while the peer already believes the window is non-opaque.
 *
 * Driving the peer's flag true and then false is the only route that makes it run `updateOpaque()`,
 * which is what calls `platformWindow.setOpaque(...)` and replaces the surface data. Both
 * assignments happen in one EDT turn, so no frame is painted between them and there is no flash.
 *
 * `setLayersOpaque` is invoked in both directions by those assignments, but it touches only the
 * layered pane, root pane and content pane and does so symmetrically, so the chain ends where it
 * started - which the `ancestors` field in the samples above is there to keep honest.
 *
 * ## Why it is gated, and gated on the platform rather than on a value
 *
 * Restricted to macOS. An earlier revision argued instead that it was naturally inert elsewhere
 * because a non-macOS window's background is null - which was wrong twice over. skiko's
 * `transparentWindowBackgroundHack` returns null on Windows with a NON-Direct3D render api, not on
 * Direct3D as that argument had it, so Windows-with-Direct3D reaches this with alpha 0 and would run
 * the cycle. And `resolveRenderingMode` returns `HARDWARE_ACCELERATED` on every platform, so Windows
 * and Linux both route overlays through here and neither was ever out of reach.
 *
 * Deriving "cannot regress" from a chain of reasoning about skiko internals was the mistake; the
 * platform check makes it true by construction. The bug is macOS, the mechanism (AppKit re-applying
 * a window's level and style bits) is macOS, and the platforms that already work should not be
 * asked to prove they survive a fix aimed at another one.
 *
 * ## Why the translucency capability is checked BEFORE the cycle
 *
 * Because a failure halfway through manufactures exactly the bug this repairs.
 * `java.awt.Window.setBackground` applies `super.setBackground` FIRST, then, on the
 * opaque-to-translucent leg, throws `IllegalComponentStateException` for a full-screen window or
 * `UnsupportedOperationException` when the graphics configuration cannot do per-pixel translucency -
 * and both throws land BEFORE `setLayersOpaque(this, false)` and before `peer.setOpaque(false)`.
 *
 * So a throw on the second assignment leaves the layered, root and content panes `opaque = true`
 * from the first, never reversed, while `window.background` already reads alpha 0. An opaque content
 * pane filling before Skia draws is the very `#FFFFFF` mechanism described above, and the sample
 * would report it as healthy. The symmetry argument for `setLayersOpaque` holds on the happy path
 * only. Hence: refuse up front where the transition cannot complete, and if it throws anyway, unwind
 * rather than leave the window worse than it was found.
 */
private fun reassertNativeTransparency(window: Window) {
    runCatching {
        if (!shouldReassertTransparency(
                backgroundAlpha = window.background?.alpha,
                isMacOs = IS_MAC_OS,
                translucencyCapable = window.graphicsConfiguration?.isTranslucencyCapable,
            )
        ) {
            return@runCatching
        }
        window.background = java.awt.Color(0, 0, 0, OPAQUE_ALPHA)
        window.background = java.awt.Color(0, 0, 0, 0)
    }.onFailure { error ->
        // Unwind before reporting. Reaching here means the second assignment threw, which leaves the
        // panes opaque from the first - so the naive "log and move on" would ship the grey backdrop
        // itself. Best effort: if this throws too there is nothing further to try.
        val unwound = runCatching { window.background = java.awt.Color(0, 0, 0, 0) }.isSuccess
        // Never fatal, for the same reason the sampling is not: a grey backdrop is ugly, an
        // exception here would take the dialog down with it.
        logger.warn(
            LogCategory.UI,
            "Could not re-assert native overlay transparency",
            mapOf(
                "error" to error.toString(),
                "unwound" to unwound.toString(),
                // The panes are what a reader needs, and the ordinary sample would call them healthy.
                "ancestors" to
                    (
                        findSkiaLayer(window)?.let { OverlayStateFormat.describeOpacityChain(it) }
                            ?: "unknown"
                    ),
            ),
        )
    }
}

/**
 * Whether the opaque/transparent cycle should run at all.
 *
 * Pure and separate because it is the entire basis of the claim that platforms which already work
 * cannot regress, and that claim lived only in prose until a review pointed out it was false. Each
 * input rules out a different way of making things worse:
 *
 *  - [isMacOs]: the bug and its mechanism are macOS-only, so nowhere else is asked to prove it
 *    survives.
 *  - [backgroundAlpha]: null (skiko leaves it so on Windows without Direct3D) or opaque means this is
 *    not a transparent overlay, and forcing translucency onto one would be inventing a change.
 *  - [translucencyCapable]: false or unknown means the transition can throw halfway and leave the
 *    panes opaque - strictly worse than doing nothing. Unknown is refused rather than attempted,
 *    because the failure mode is the bug itself.
 */
internal fun shouldReassertTransparency(
    backgroundAlpha: Int?,
    isMacOs: Boolean,
    translucencyCapable: Boolean?,
): Boolean = isMacOs && backgroundAlpha == 0 && translucencyCapable == true

/** The alpha that makes AWT consider a window opaque, and so the value that forces the transition. */
private const val OPAQUE_ALPHA = 255

/** Read once: the host OS does not change, and this is consulted on every overlay open. */
private val IS_MAC_OS =
    System
        .getProperty("os.name")
        .orEmpty()
        .lowercase()
        .contains("mac")

/** One sample of every layer that decides whether an overlay window paints opaque. */
private fun logOverlayTransparency(
    window: Window,
    kind: String,
    phase: String,
) {
    // Gate on the level BEFORE doing any of the work. `logger.debug(cat, msg, mapOf(...))` builds
    // its map eagerly, so without this the DEBUG demotion saved nothing: every overlay open still
    // paid, three times, for a recursive component-tree walk, two reflective lookups and a
    // String.format per ancestor - all on the EDT while the window is opening.
    if (BossLogger.getEffectiveLevel(LogCategory.UI).priority > LogLevel.DEBUG.priority) return
    runCatching {
        val layer = findSkiaLayer(window)
        val transparency = layer?.let { SkiaLayerProbe.transparency(it) }
        // Read through getBackground(), which INHERITS from the AWT parent when the layer's own
        // background is null - deliberately, because skiko's clear calls the same getter and so
        // resolves the same colour. Reading the private field instead would report "null" for
        // precisely the state under suspicion (Compose sets the layer background to null on the
        // Metal path) and hide the opaque colour that is actually being painted.
        val layerBackground = layer?.background
        logger.debug(
            LogCategory.UI,
            "Overlay window transparency sample",
            mapOf(
                "kind" to kind,
                "phase" to phase,
                "displayable" to window.isDisplayable.toString(),
                "showing" to window.isShowing.toString(),
                "layerFound" to (layer != null).toString(),
                "transparency" to transparency.toString(),
                "layerBackground" to OverlayStateFormat.describeColor(layerBackground),
                "renderApi" to (layer?.let { SkiaLayerProbe.renderApi(it) } ?: "unknown"),
                "windowBackground" to OverlayStateFormat.describeColor(window.background),
                "translucencyCapable" to
                    (
                        runCatching {
                            window.graphicsConfiguration
                                ?.isTranslucencyCapable
                                ?.toString()
                        }.getOrNull() ?: "unknown"
                    ),
                "willPaintOpaque" to
                    overlayWillPaintOpaque(transparency, layerBackground?.alpha).toString(),
                // The Swing layers ABOVE the Skia surface, because a correct Skia layer is not
                // sufficient. Anything between the layer and the window that is `opaque` fills its
                // own background before Skia draws, and Skia then draws fully transparent pixels
                // over it - which composites to that fill and to nothing else. Compose sets
                // ComposeWindowPanel.isOpaque = false for exactly this reason, so an ancestor found
                // opaque here is the whole answer, and one found opaque with a WHITE background is
                // the measured #FFFFFF itself.
                "ancestors" to (layer?.let { OverlayStateFormat.describeOpacityChain(it) } ?: "unknown"),
            ),
        )
        if (layer == null) {
            // DEBUG with the rest, not WARN. If a future Compose version moves the layer again this
            // fires for every overlay, every phase, and a tree dump each time - a flood reporting a
            // diagnostic's own blindness, not a user-visible fault.
            logger.debug(
                LogCategory.UI,
                "Overlay window transparency sample found no Skia layer",
                mapOf(
                    "kind" to kind,
                    "phase" to phase,
                    "tree" to OverlayStateFormat.describeComponentTree(window).joinToString(" | "),
                ),
            )
        }
    }.onFailure {
        // An overlay must never be taken down by its own instrumentation - the same rule the
        // re-assert above already follows.
        logger.warn(
            LogCategory.UI,
            "Could not sample overlay window transparency",
            mapOf("kind" to kind, "phase" to phase, "error" to it.toString()),
        )
    }
}

/**
 * Whether the Skia surface will be cleared to an OPAQUE colour, mirroring skiko's own rule.
 *
 * `SkiaLayer` clears with `bg` when `transparency` is on and the redrawer supports a transparent
 * background (unconditionally true on macOS), and with `bg or 0xFF000000` otherwise. So the surface
 * is transparent only when the flag is on AND the background it clears to has zero alpha.
 *
 * **`false` here does NOT mean the overlay looks right, and that is not a shortcoming to be fixed.**
 * It answers one question - what colour Skia clears to - and it was answered `false` on a window
 * that was visibly grey at the moment of the sample. That reading is what proved the opacity lives
 * below Java and killed the Java-side candidate fix, so the value of this predicate came from
 * disagreeing with the screen, not from agreeing with it. Read it as evidence about one layer, never
 * as a health check: the layer it describes is not the last one that can paint.
 *
 * **Unknown reads as opaque.** Both inputs are null when reflection could not reach the layer, and
 * a diagnostic that reported "healthy" for a window it failed to inspect would be worse than no
 * diagnostic - it is the one answer that cannot be distinguished from a genuine pass.
 */
internal fun overlayWillPaintOpaque(
    transparency: Boolean?,
    backgroundAlpha: Int?,
): Boolean = transparency != true || backgroundAlpha != 0

/**
 * Whether [name] is skiko's layer class.
 *
 * Split out so the match is pinned by a test: a headless test cannot construct a real `SkiaLayer`
 * (it needs a display and a render device), so the class-name predicate is the only part of
 * [findSkiaLayer] a unit test can reach - and it is the part that silently stops matching if skiko
 * ever moves the class.
 */
internal fun isSkiaLayerClassName(name: String): Boolean = name == "org.jetbrains.skiko.SkiaLayer"

/**
 * Whether [type] IS skiko's layer, or any subclass of it.
 *
 * The superclass walk is not defensive padding, it is the difference between reporting and not
 * reporting: matching the exact name alone found nothing at all on a live 1.11.1 build, at every
 * phase, on a window that was demonstrably rendering. Compose instantiates the layer as its own
 * subclass, so `javaClass.name` is never the skiko name. Anything that only ever reads public
 * `SkiaLayer` members is correct against a subclass too.
 */
private fun isSkiaLayerClass(type: Class<*>): Boolean =
    generateSequence<Class<*>>(type) { it.superclass }.any { isSkiaLayerClassName(it.name) }

/**
 * The skiko layer inside [root], found by walking the AWT component tree.
 *
 * Matched by class identity and read reflectively rather than through a typed dependency.
 * `composeApp` declares no skiko dependency and gets it only transitively from Compose, so
 * declaring one would pin a version that has to be kept in step with every Compose bump - for code
 * whose only job is to report a field.
 */
private fun findSkiaLayer(root: java.awt.Component): javax.swing.JComponent? =
    if (isSkiaLayerClass(root.javaClass)) {
        root as? javax.swing.JComponent
    } else {
        (root as? java.awt.Container)
            ?.components
            ?.firstNotNullOfOrNull { findSkiaLayer(it) }
    }

/**
 * Rendering of the observed state into log fields.
 *
 * Grouped into an object purely so the reporting vocabulary sits together and apart from the
 * reading of it - the file is otherwise a flat list where a formatter and a probe look alike.
 */
private object OverlayStateFormat {
    /**
     * Every component from [from] up to its window, as `ClassName(opaque=…, bg=…)`.
     *
     * The chain, not just one component, because any single opaque link is enough: Swing paints an
     * opaque component's background before its children draw, so one opaque ancestor with a white fill
     * produces exactly the measured backdrop no matter how correct the Skia layer below it is. Reading
     * only the layer answered "everything is fine" on a window that was visibly grey.
     */
    fun describeOpacityChain(from: javax.swing.JComponent): String =
        generateSequence<java.awt.Component>(from) { it.parent }
            .take(OPACITY_CHAIN_MAX_LINKS)
            .joinToString(" < ") { component ->
                val opaque = (component as? javax.swing.JComponent)?.isOpaque ?: component.isOpaque
                val name = component.javaClass.simpleName
                "$name(opaque=$opaque, bg=${describeColor(component.background)})"
            }

    /** `Color` with its alpha spelled out, since the alpha is the whole point of every reading here. */
    fun describeColor(color: java.awt.Color?): String =
        color?.let { "#%02x%02x%02x alpha=%d".format(it.red, it.green, it.blue, it.alpha) } ?: "null"

    /**
     * The class names on the path from [root] down the AWT tree, for when [findSkiaLayer] finds
     * nothing.
     *
     * Logged only in that case, and bounded. A `layerFound=false` line is otherwise an unreadable
     * result rather than a reading - it says the diagnostic failed without saying what it saw, which is
     * exactly how the first run of this instrumentation burned a whole app launch.
     */
    fun describeComponentTree(
        root: java.awt.Component,
        depth: Int = 0,
    ): List<String> =
        if (depth > TREE_DUMP_MAX_DEPTH) {
            emptyList()
        } else {
            listOf("  ".repeat(depth) + root.javaClass.name) +
                ((root as? java.awt.Container)?.components.orEmpty())
                    .flatMap { describeComponentTree(it, depth + 1) }
        }
}

/** Deep enough to reach the Skia layer under a root pane, shallow enough not to flood the log. */
private const val TREE_DUMP_MAX_DEPTH = 6

/**
 * Bound on the walk UP from the Skia layer to the window.
 *
 * Its own constant rather than a multiple of the downward depth limit: the two measure opposite
 * directions and only coincidentally suit the same tree, so tuning one for a deeper hierarchy would
 * silently move the other.
 */
private const val OPACITY_CHAIN_MAX_LINKS = 12

/**
 * Reflective readers for the two `SkiaLayer` properties worth sampling, with their lookups cached.
 *
 * Keyed by the concrete class because Compose subclasses `SkiaLayer` and a build could in principle
 * see more than one. `getMethod` is not free and this runs on the EDT while a window opens.
 */
private object SkiaLayerProbe {
    private val accessors =
        java.util.concurrent.ConcurrentHashMap<Class<*>, Map<String, java.lang.reflect.Method?>>()

    private fun accessor(
        layer: javax.swing.JComponent,
        name: String,
    ): java.lang.reflect.Method? =
        accessors.computeIfAbsent(layer.javaClass) { type ->
            listOf("getTransparency", "getRenderApi")
                .associateWith { runCatching { type.getMethod(it) }.getOrNull() }
        }[name]

    /** `SkiaLayer.transparency`, or null if it could not be read. Never throws. */
    fun transparency(layer: javax.swing.JComponent): Boolean? =
        runCatching { accessor(layer, "getTransparency")?.invoke(layer) as? Boolean }.getOrNull()

    /** `SkiaLayer.renderApi` as a name (METAL, SOFTWARE_FAST, ...), or null. Never throws. */
    fun renderApi(layer: javax.swing.JComponent): String? =
        runCatching { accessor(layer, "getRenderApi")?.invoke(layer)?.toString() }.getOrNull()
}
