package ai.rever.boss.ui.sdk

import ai.rever.boss.ui.sdk.WidgetProtoConverter.toKotlin
import ai.rever.boss.ui.sdk.WidgetProtoConverter.toProto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import ai.rever.boss.ipc.proto.UIEvent as ProtoUIEvent
import ai.rever.boss.ipc.proto.WidgetModifier as ProtoWidgetModifier
import ai.rever.boss.ipc.proto.WidgetNode as ProtoWidgetNode
import ai.rever.boss.ipc.proto.WidgetTree as ProtoWidgetTree
import ai.rever.boss.ipc.proto.WidgetType as ProtoWidgetType

/**
 * End-to-end coverage of the interactive path for issue #34 items 1 and 2:
 * **builder → wire → renderer resolution → event back over the wire**.
 *
 * The renderer step is represented by the SDK resolution rules the Compose renderer now calls
 * ([resolveClickEventId], [resolveDropdownOptions]) — that is where the mismatch lived: the builder
 * wrote `onClickEvent`, the renderer read `clickEventId`, and nothing failed loudly. Everything
 * except the Compose call itself is exercised here.
 */
class RemoteUiInteractionWireTest {
    private val surface = "panel-1"

    /** A tree as it arrives at a host: built by the plugin SDK, serialized, deserialized. */
    private fun overTheWire(tree: WidgetTree): WidgetTree = tree.toProto().toKotlin()

    private fun WidgetTree.only(type: WidgetType): WidgetNode = nodes.values.single { it.type == type }

    @Test
    fun `a builder-built button click reaches the plugin with its event id`() {
        val sent =
            widgetTree {
                column {
                    text("Settings")
                    button("Save", "save_settings")
                }
            }

        val received = overTheWire(sent)
        val button = received.only(WidgetType.BUTTON)

        // Renderer step: what event id does a click on this node report?
        val eventId = button.resolveClickEventId()
        assertEquals("save_settings", eventId, "builder-built buttons used to click with an empty id")

        // Back over the wire to the plugin.
        val wire = UIEventMapper.toProto(surface, button.id, WidgetEvent.Click(eventId), 42L)
        assertEquals(ProtoUIEvent.EventCase.CLICK, wire.eventCase)
        assertEquals("save_settings", wire.click.eventId)
        assertEquals(button.id, wire.targetNodeId)
        assertEquals(surface, wire.surfaceId)

        assertEquals(EmittedEvent(button.id, WidgetEvent.Click("save_settings")), UIEventMapper.fromProto(wire))
    }

    @Test
    fun `a dropdown selection reaches the plugin with its value and index`() {
        val sent =
            widgetTree {
                column {
                    dropdown("beta", listOf("alpha", "beta", "gamma"), "pick_channel")
                }
            }

        val received = overTheWire(sent)
        val dropdown = received.only(WidgetType.DROPDOWN)

        // Renderer step: the options it draws, and the pick it turns into an event.
        val options = dropdown.resolveDropdownOptions()
        assertEquals(listOf("alpha", "beta", "gamma"), options)
        val picked = 2
        val event = WidgetEvent.Selection(options[picked], picked)

        val wire = UIEventMapper.toProto(surface, dropdown.id, event, 42L)
        assertEquals(ProtoUIEvent.EventCase.SELECTION, wire.eventCase)
        assertEquals("gamma", wire.selection.selectedValue)
        assertEquals(picked, wire.selection.selectedIndex)

        assertEquals(EmittedEvent(dropdown.id, event), UIEventMapper.fromProto(wire))
    }

    @Test
    fun `a text field edit reaches the plugin with the full new value`() {
        val received = overTheWire(widgetTree { textField("", "name_changed", "Name") })
        val field = received.only(WidgetType.TEXT_FIELD)

        val wire = UIEventMapper.toProto(surface, field.id, WidgetEvent.TextChange("Ada"), 42L)
        assertEquals(EmittedEvent(field.id, WidgetEvent.TextChange("Ada")), UIEventMapper.fromProto(wire))
    }

    @Test
    fun `a plugin built against the old SDK still clicks`() {
        // Trees from already-shipped plugins carry only the legacy spelling.
        val legacy =
            WidgetTree(
                rootId = "b1",
                nodes =
                    mapOf(
                        "b1" to
                            WidgetNode(
                                "b1",
                                WidgetType.BUTTON,
                                properties = mapOf("label" to "Save", PROP_ON_CLICK_EVENT to "save_settings"),
                            ),
                    ),
            )

        assertEquals("save_settings", overTheWire(legacy).only(WidgetType.BUTTON).resolveClickEventId())
    }

    @Test
    fun `a host that only writes the canonical spelling still clicks`() {
        val canonical =
            WidgetTree(
                rootId = "b1",
                nodes =
                    mapOf(
                        "b1" to
                            WidgetNode(
                                "b1",
                                WidgetType.BUTTON,
                                properties = mapOf("label" to "Save", PROP_CLICK_EVENT_ID to "save_settings"),
                            ),
                    ),
            )

        assertEquals("save_settings", overTheWire(canonical).only(WidgetType.BUTTON).resolveClickEventId())
    }

    @Test
    fun `a clickable modifier click reaches the plugin`() {
        val tree =
            WidgetTree(
                rootId = "box1",
                nodes =
                    mapOf(
                        "box1" to
                            WidgetNode(
                                "box1",
                                WidgetType.BOX,
                                modifier = WidgetModifier(clickable = true, clickEventId = "surface_tapped"),
                            ),
                    ),
            )

        val box = overTheWire(tree).nodes.getValue("box1")
        assertTrue(box.modifier.clickable)
        assertEquals("surface_tapped", box.resolveClickEventId())
    }

    @Test
    fun `list rows survive the wire so the renderer can draw them`() {
        val received = overTheWire(widgetTree { list(listOf("first", "second", "third")) })
        val list = received.only(WidgetType.LIST)

        assertTrue(list.childIds.isEmpty(), "the builder emits rows as a property, not child nodes")
        assertEquals(listOf("first", "second", "third"), list.resolveListItems())
    }

    /** A tree as a plugin that builds the proto directly would send it: `alpha` never touched. */
    private fun protoTextTree(): ProtoWidgetTree =
        ProtoWidgetTree
            .newBuilder()
            .setRootId("t1")
            .addNodes(
                ProtoWidgetNode
                    .newBuilder()
                    .setId("t1")
                    .setType(ProtoWidgetType.WIDGET_TYPE_TEXT)
                    .putProperties("value", "Hello")
                    // Modifier present, alpha never set.
                    .setModifier(ProtoWidgetModifier.newBuilder().setPaddingTop(4)),
            ).build()

    @Test
    fun `a sender that never touches alpha is treated as unset, not invisible`() {
        // The real-world shape of issue #34 item 4: proto3's default 0.0 must not mean "transparent".
        val text = protoTextTree().toKotlin().nodes.getValue("t1")

        assertEquals(1f, text.modifier.alpha, "an unset wire alpha is canonicalized to opaque")
        assertNull(text.modifier.effectiveAlpha(), "and composites nothing")
    }

    @Test
    fun `a wire-originated tree diffs clean against an identical local one`() {
        // Before alpha was canonicalized at the boundary, the wire side carried 0f and the local side
        // 1f for the same opaque widget, so WidgetModifier equality failed and the FIRST diff after a
        // tree arrived reported a modifier change for every single node.
        val fromWire = protoTextTree().toKotlin()
        val local =
            WidgetTree(
                rootId = "t1",
                nodes =
                    mapOf(
                        "t1" to
                            WidgetNode(
                                "t1",
                                WidgetType.TEXT,
                                properties = mapOf("value" to "Hello"),
                                modifier = WidgetModifier(paddingTop = 4),
                            ),
                    ),
            )

        assertEquals(local.nodes, fromWire.nodes)
        assertTrue(
            WidgetDiffEngine.diff(fromWire, local).isEmpty(),
            "expected no ops, got: ${WidgetDiffEngine.diff(fromWire, local)}",
        )
    }

    @Test
    fun `the Kotlin default alpha needs no compositing`() {
        val text = overTheWire(widgetTree { text("Hello") }).only(WidgetType.TEXT)

        assertEquals(1f, text.modifier.alpha)
        assertNull(text.modifier.effectiveAlpha())
    }

    @Test
    fun `a real alpha survives the wire`() {
        val tree =
            WidgetTree(
                rootId = "t1",
                nodes = mapOf("t1" to WidgetNode("t1", WidgetType.TEXT, modifier = WidgetModifier(alpha = 0.25f))),
            )

        assertEquals(
            0.25f,
            overTheWire(tree)
                .nodes
                .getValue("t1")
                .modifier
                .effectiveAlpha(),
        )
    }

    @Test
    fun `a theme-token background survives the wire`() {
        val tree =
            WidgetTree(
                rootId = "c1",
                nodes =
                    mapOf(
                        "c1" to
                            WidgetNode(
                                "c1",
                                WidgetType.COLUMN,
                                modifier = WidgetModifier(backgroundColor = "raised"),
                            ),
                    ),
            )

        assertEquals(
            BackgroundSpec.Token(ThemeToken.RAISED),
            overTheWire(tree)
                .nodes
                .getValue("c1")
                .modifier
                .resolveBackground(),
        )
    }
}
