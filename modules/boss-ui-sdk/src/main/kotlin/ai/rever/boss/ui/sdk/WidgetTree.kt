package ai.rever.boss.ui.sdk

data class WidgetTree(
    val rootId: String,
    val nodes: Map<String, WidgetNode>,
    val version: Long = 0,
)

data class WidgetNode(
    val id: String,
    val type: WidgetType,
    val properties: Map<String, String> = emptyMap(),
    val childIds: List<String> = emptyList(),
    val modifier: WidgetModifier = WidgetModifier(),
)

/**
 * Layout/style modifiers. Mirrors proto `WidgetModifier`.
 *
 * @param width `0` wraps content, positive values are dp, `-1` fills the available space.
 * @param height same encoding as [width].
 * @param backgroundColor a hex string (`#RRGGBB` / `#AARRGGBB`) **or** a theme-token name
 *   (`panel`, `raised`, `signalWash`, …) — see [ThemeToken] and [parseBackgroundColor].
 * @param alpha opacity in `0.0..1.0`. **`0.0` means "unset", not "invisible"**: proto3 has no
 *   presence for scalars, so an unset `alpha` arrives as `0.0` and honouring it literally would
 *   make every widget from every existing plugin disappear. Renderers must resolve it through
 *   [effectiveAlpha], which treats `<= 0` (and `>= 1`) as "nothing to apply". A widget that
 *   should be invisible must simply not be sent.
 */
data class WidgetModifier(
    val width: Int = 0,
    val height: Int = 0,
    val paddingStart: Int = 0,
    val paddingTop: Int = 0,
    val paddingEnd: Int = 0,
    val paddingBottom: Int = 0,
    val backgroundColor: String = "",
    val alpha: Float = 1f,
    val clickable: Boolean = false,
    val clickEventId: String = "",
)

enum class WidgetType {
    COLUMN,
    ROW,
    BOX,
    SCROLL,
    TEXT,
    ICON,
    IMAGE,
    DIVIDER,
    SPACER,
    PROGRESS,
    BUTTON,
    TEXT_FIELD,
    CHECKBOX,
    DROPDOWN,
    TOGGLE,
    LIST,
    TREE,
    TABLE,
    TAB_ROW,
    CODE_EDITOR,
    TERMINAL,
    BROWSER,
    CANVAS,
}
