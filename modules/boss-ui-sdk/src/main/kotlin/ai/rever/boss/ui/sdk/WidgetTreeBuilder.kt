package ai.rever.boss.ui.sdk

fun widgetTree(block: WidgetTreeBuilder.() -> Unit): WidgetTree {
    val builder = WidgetTreeBuilder()
    builder.block()
    return builder.buildTree()
}

/**
 * DSL for building a [WidgetTree]. The first widget created becomes the root.
 *
 * ## Node identity
 *
 * Ids are **deterministic**: `w0`, `w1`, … in creation (pre-)order, assigned by a counter that is
 * private to one builder instance. Rebuilding a structurally unchanged tree therefore yields the
 * same ids.
 *
 * That is load-bearing, not cosmetic. Node ids *are* node identity for [WidgetDiffEngine] and for
 * host-side per-node state (a text field's edit buffer, a scroll offset). With the random UUIDs this
 * builder used to mint, every rebuild produced an all-new set of ids, so each update degenerated
 * into remove-everything/add-everything and any state the host held against a node id was thrown
 * away — the visible symptom being a text field that lost what the user was typing whenever the
 * plugin refreshed its tree.
 *
 * ## The limit of positional ids
 *
 * Ids track *position*, so **inserting or removing a widget renumbers everything after it in
 * creation order**. For that suffix the diff degenerates to remove-all/add-all and any host-side
 * state keyed by node id is discarded — the same symptom this addresses, triggered structurally
 * instead of on every rebuild. Deterministic ids fix the common case (rebuild the same shape with
 * new values, which is what a plugin does on nearly every update) and leave structural edits no
 * worse than before.
 *
 * Real stability under insertion needs a caller-supplied key
 * (`button(label, event, key = "search")`), where the id derives from the key rather than the
 * counter. That is a follow-up: it touches every builder entry point, and adding a defaulted
 * parameter to each would change their JVM descriptors — a binary break for plugins that pin an
 * older `boss-ui-sdk` jar. It wants overloads and a deprecation window, not a drive-by parameter.
 */
class WidgetTreeBuilder {
    private val allNodes = mutableMapOf<String, WidgetNode>()
    private val childrenOf = mutableMapOf<String, MutableList<String>>()
    private val parentStack = ArrayDeque<String>()
    private var rootId: String? = null
    private var nextId = 0

    private fun genId(): String = "w${nextId++}"

    private fun container(
        type: WidgetType,
        properties: Map<String, String> = emptyMap(),
        modifier: WidgetModifier = WidgetModifier(),
        block: WidgetTreeBuilder.() -> Unit,
    ): String {
        val id = genId()
        childrenOf[id] = mutableListOf()
        parentStack.lastOrNull()?.let { childrenOf[it]?.add(id) }
        if (rootId == null) rootId = id
        parentStack.addLast(id)
        block()
        parentStack.removeLast()
        allNodes[id] = WidgetNode(id, type, properties, childrenOf[id] ?: emptyList(), modifier)
        return id
    }

    private fun leaf(
        type: WidgetType,
        properties: Map<String, String> = emptyMap(),
        modifier: WidgetModifier = WidgetModifier(),
    ): String {
        val id = genId()
        parentStack.lastOrNull()?.let { childrenOf[it]?.add(id) }
        if (rootId == null) rootId = id
        allNodes[id] = WidgetNode(id, type, properties, emptyList(), modifier)
        return id
    }

    fun column(
        modifier: WidgetModifier = WidgetModifier(),
        block: WidgetTreeBuilder.() -> Unit,
    ): String = container(WidgetType.COLUMN, emptyMap(), modifier, block)

    fun row(
        modifier: WidgetModifier = WidgetModifier(),
        block: WidgetTreeBuilder.() -> Unit,
    ): String = container(WidgetType.ROW, emptyMap(), modifier, block)

    fun box(
        modifier: WidgetModifier = WidgetModifier(),
        block: WidgetTreeBuilder.() -> Unit,
    ): String = container(WidgetType.BOX, emptyMap(), modifier, block)

    fun scroll(
        modifier: WidgetModifier = WidgetModifier(),
        block: WidgetTreeBuilder.() -> Unit,
    ): String = container(WidgetType.SCROLL, emptyMap(), modifier, block)

    fun text(
        value: String,
        style: String = "",
    ): String =
        leaf(
            WidgetType.TEXT,
            buildMap {
                put("value", value)
                if (style.isNotEmpty()) put("style", style)
            },
        )

    /**
     * A button that reports [onClickEvent] when clicked.
     *
     * The event id is written under **both** [PROP_CLICK_EVENT_ID] (the spelling renderers read) and
     * [PROP_ON_CLICK_EVENT] (the spelling this builder has always written). Writing only
     * `onClickEvent` meant every builder-built button clicked with an empty event id; writing only
     * `clickEventId` would break hosts that were shipped reading the old spelling. Renderers resolve
     * either via [resolveClickEventId].
     */
    fun button(
        label: String,
        onClickEvent: String,
    ): String =
        leaf(
            WidgetType.BUTTON,
            mapOf(
                "label" to label,
                PROP_CLICK_EVENT_ID to onClickEvent,
                PROP_ON_CLICK_EVENT to onClickEvent,
            ),
        )

    fun textField(
        value: String,
        onChangeEvent: String,
        placeholder: String = "",
    ): String =
        leaf(
            WidgetType.TEXT_FIELD,
            buildMap {
                put("value", value)
                put("onChangeEvent", onChangeEvent)
                if (placeholder.isNotEmpty()) put("placeholder", placeholder)
            },
        )

    fun icon(
        name: String,
        size: Int = 24,
    ): String = leaf(WidgetType.ICON, mapOf("name" to name, "size" to size.toString()))

    fun checkbox(
        checked: Boolean,
        onToggleEvent: String,
        label: String = "",
    ): String =
        leaf(
            WidgetType.CHECKBOX,
            buildMap {
                put("checked", checked.toString())
                put("onToggleEvent", onToggleEvent)
                if (label.isNotEmpty()) put("label", label)
            },
        )

    fun dropdown(
        selected: String,
        options: List<String>,
        onSelectEvent: String,
    ): String =
        leaf(
            WidgetType.DROPDOWN,
            mapOf(
                "selected" to selected,
                PROP_OPTIONS to options.joinToString(","),
                "onSelectEvent" to onSelectEvent,
            ),
        )

    fun progress(
        value: Float = 0f,
        indeterminate: Boolean = false,
    ): String =
        leaf(
            WidgetType.PROGRESS,
            mapOf(
                "value" to value.toString(),
                "indeterminate" to indeterminate.toString(),
            ),
        )

    fun spacer(height: Int = 0): String = leaf(WidgetType.SPACER, mapOf("height" to height.toString()))

    fun divider(): String = leaf(WidgetType.DIVIDER)

    /**
     * A list of plain text rows, carried as a comma-joined [PROP_ITEMS] property rather than child
     * nodes. Renderers draw child nodes when present and fall back to these rows
     * (see [resolveListItems]) — a renderer that only walked `childIds` drew nothing at all.
     */
    fun list(items: List<String>): String = leaf(WidgetType.LIST, mapOf(PROP_ITEMS to items.joinToString(",")))

    internal fun buildTree(): WidgetTree {
        val root = rootId ?: throw IllegalStateException("No root widget defined")
        return WidgetTree(root, allNodes.toMap())
    }
}
