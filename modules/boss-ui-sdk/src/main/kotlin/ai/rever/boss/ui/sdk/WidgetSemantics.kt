package ai.rever.boss.ui.sdk

/**
 * Property/modifier interpretation rules shared by every widget-tree renderer.
 *
 * These live in the SDK rather than inside a renderer on purpose: they are the *contract* between
 * [WidgetTreeBuilder] (plugin side) and whatever draws the tree (host side, Compose today, the
 * native `boss-remote-ui` renderer in the Rust shell). Keeping them here means both ends resolve a
 * node identically and the rules are unit-testable without a UI toolkit.
 */

// ---- Property names -------------------------------------------------------

/** Canonical spelling of a button's click event id — what renderers read first. */
const val PROP_CLICK_EVENT_ID: String = "clickEventId"

/** Legacy spelling written by [WidgetTreeBuilder.button]; accepted as a fallback. */
const val PROP_ON_CLICK_EVENT: String = "onClickEvent"

/** Comma-joined row labels written by [WidgetTreeBuilder.list]. */
const val PROP_ITEMS: String = "items"

/** Comma-joined option labels written by [WidgetTreeBuilder.dropdown]. */
const val PROP_OPTIONS: String = "options"

private const val UNSET_ALPHA = 0f
private const val OPAQUE_ALPHA = 1f

/**
 * Resolve the event id a click on this node should report.
 *
 * Precedence: [PROP_CLICK_EVENT_ID], then [PROP_ON_CLICK_EVENT], then [WidgetModifier.clickEventId];
 * empty values fall through. Both spellings are accepted because
 * `WidgetTreeBuilder.button()` historically wrote only `onClickEvent` while renderers read only
 * `clickEventId` — every builder-built button clicked with an empty id. The builder now writes both
 * (so already-shipped hosts work) and renderers accept either (so already-shipped plugins work).
 */
fun WidgetNode.resolveClickEventId(): String =
    properties[PROP_CLICK_EVENT_ID]?.takeIf(String::isNotEmpty)
        ?: properties[PROP_ON_CLICK_EVENT]?.takeIf(String::isNotEmpty)
        ?: modifier.clickEventId

/**
 * Row labels for a `LIST` node that carries no child nodes.
 *
 * [WidgetTreeBuilder.list] writes a comma-joined `items` property instead of child nodes, so a
 * renderer that only walks `childIds` draws an empty list. Renderers should prefer children and
 * fall back to this.
 */
fun WidgetNode.resolveListItems(): List<String> = splitCommaProperty(PROP_ITEMS)

/** Option labels for a `DROPDOWN` node. Empty (not `[""]`) when the property is absent or blank. */
fun WidgetNode.resolveDropdownOptions(): List<String> = splitCommaProperty(PROP_OPTIONS)

/**
 * Split a comma-joined property. The rule, stated once so both renderers agree (and repeated in
 * `ui_protocol.proto`):
 *
 * - split on `,`; **no escaping, no trimming** — `"Buy milk, eggs"` is two entries, the second
 *   being `" eggs"`. A value that contains a comma must be sent as child nodes instead.
 * - an *absent or empty* property yields no entries. This is not the same as an intentionally empty
 *   element: `"a,,b"` is three entries, the middle one blank. Kotlin's `"".split(",")` would
 *   otherwise hand a renderer one blank entry for a property nobody set.
 */
private fun WidgetNode.splitCommaProperty(key: String): List<String> =
    properties[key]?.takeIf { it.isNotEmpty() }?.split(",") ?: emptyList()

/**
 * The opacity a renderer should actually apply, or `null` when there is nothing to apply.
 *
 * Resolves the proto3 presence gap documented on [WidgetModifier.alpha]: `<= 0` means "unset"
 * (proto3's default for an untouched float), `>= 1` means fully opaque, and `NaN` is garbage.
 * Only a value strictly inside `(0, 1)` is a real request.
 */
fun WidgetModifier.effectiveAlpha(): Float? =
    when {
        alpha.isNaN() -> null
        alpha <= UNSET_ALPHA -> null
        alpha >= OPAQUE_ALPHA -> null
        else -> alpha
    }

/**
 * Canonicalize an `alpha` value arriving off the wire: everything the sentinel treats as "nothing to
 * apply" becomes [OPAQUE_ALPHA], the Kotlin default.
 *
 * Without this, the *same* widget is unequal to itself depending on where it came from — a builder
 * modifier carries `alpha = 1f` while an untouched proto field arrives as `0f`, and both mean
 * "opaque". [WidgetDiffEngine] compares whole [WidgetModifier]s, so a diff whose old side came off
 * the wire reported a modifier change for **every node** on the first update. Normalizing at the
 * boundary keeps the sentinel and makes structural equality mean semantic equality.
 *
 * The cost is that `toProto(toKotlin(p))` rewrites an unset `0.0` as `1.0`. Those encode the same
 * rendering, so the round trip is semantics-preserving rather than byte-preserving.
 */
fun normalizeWireAlpha(alpha: Float): Float =
    when {
        alpha.isNaN() -> OPAQUE_ALPHA
        alpha <= UNSET_ALPHA -> OPAQUE_ALPHA
        alpha > OPAQUE_ALPHA -> OPAQUE_ALPHA
        else -> alpha
    }

// ---- Background color -----------------------------------------------------

/**
 * The BOSS design-system color tokens a plugin may name in [WidgetModifier.backgroundColor].
 *
 * One entry per `BossColorScheme` field. Token names resolve against the *host's* active theme, so
 * a plugin that asks for `panel` re-skins with the app instead of pinning a hex value.
 */
enum class ThemeToken(
    val tokenName: String,
) {
    INK("ink"),
    PANEL("panel"),
    RAISED("raised"),
    LINE("line"),
    LINE_STRONG("lineStrong"),
    TEXT_PRIMARY("textPrimary"),
    TEXT_SECONDARY("textSecondary"),
    TEXT_MUTED("textMuted"),
    SIGNAL("signal"),
    SIGNAL_DIM("signalDim"),
    SIGNAL_WASH("signalWash"),
    SIGNAL_TEXT("signalText"),
    DATA("data"),
    OK("ok"),
    WARN("warn"),
    ALERT("alert"),
    ON_SIGNAL("onSignal"),
    ON_DATA("onData"),
    ;

    companion object {
        private val byNormalizedName: Map<String, ThemeToken> =
            entries.associateBy { normalize(it.tokenName) }

        private fun normalize(spec: String): String = spec.filter { it != '_' && it != '-' }.lowercase()

        /** Match a wire spec against the token names, accepting `camelCase`, `snake_case` and `kebab-case`. */
        fun fromSpec(spec: String): ThemeToken? = byNormalizedName[normalize(spec)]
    }
}

/** What a [WidgetModifier.backgroundColor] string resolves to. */
sealed interface BackgroundSpec {
    /** A design-system token; the renderer looks it up in the host's active color scheme. */
    data class Token(
        val token: ThemeToken,
    ) : BackgroundSpec

    /** A literal color as packed `0xAARRGGBB` (opaque alpha filled in for the `#RRGGBB` form). */
    data class Hex(
        val argb: Long,
    ) : BackgroundSpec

    /** Absent, or a spec that is neither a known token nor valid hex — draw no background. */
    data object None : BackgroundSpec
}

private const val RGB_HEX_LENGTH = 6
private const val ARGB_HEX_LENGTH = 8
private const val OPAQUE_ALPHA_BITS = 0xFF000000L

/**
 * Resolve a [WidgetModifier.backgroundColor] spec.
 *
 * Tokens win over hex (they cannot collide: no token name is valid hex). Unknown specs resolve to
 * [BackgroundSpec.None] rather than throwing — a plugin typo must not take the surface down.
 */
fun parseBackgroundColor(spec: String): BackgroundSpec =
    when {
        spec.isEmpty() -> {
            BackgroundSpec.None
        }

        else -> {
            ThemeToken.fromSpec(spec)?.let(BackgroundSpec::Token)
                ?: parseHexArgb(spec)?.let(BackgroundSpec::Hex)
                ?: BackgroundSpec.None
        }
    }

/** Shorthand for `parseBackgroundColor(backgroundColor)`. */
fun WidgetModifier.resolveBackground(): BackgroundSpec = parseBackgroundColor(backgroundColor)

/**
 * Parse `#RRGGBB` / `#AARRGGBB` (with or without the leading `#`) into packed `0xAARRGGBB`.
 * `null` for anything else — including the 3-digit `#RGB` shorthand, which this protocol has never
 * accepted.
 */
private fun parseHexArgb(hex: String): Long? {
    val clean = hex.trimStart('#')
    return when {
        !clean.all { it.isHexDigit() } -> null
        clean.length == RGB_HEX_LENGTH -> OPAQUE_ALPHA_BITS or clean.toLong(16)
        clean.length == ARGB_HEX_LENGTH -> clean.toLong(16)
        else -> null
    }
}

private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
