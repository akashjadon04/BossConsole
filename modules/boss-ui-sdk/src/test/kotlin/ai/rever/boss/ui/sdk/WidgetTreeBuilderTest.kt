package ai.rever.boss.ui.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WidgetTreeBuilderTest {
    @Test
    fun `build tree column with text button and row`() {
        val tree =
            widgetTree {
                column {
                    text("Hello")
                    button("Click me", "click1")
                    row {
                        icon("star", 24)
                        text("World")
                    }
                }
            }

        // column + text + button + row + icon + text = 6 nodes
        assertEquals(6, tree.nodes.size)

        val root = tree.nodes[tree.rootId]
        assertNotNull(root)
        assertEquals(WidgetType.COLUMN, root.type)
        assertEquals(3, root.childIds.size)
    }

    @Test
    fun `verify parent-child relationships`() {
        val tree =
            widgetTree {
                column {
                    text("Hello")
                    button("Click me", "click1")
                    row {
                        icon("star", 24)
                        text("World")
                    }
                }
            }

        val root = tree.nodes[tree.rootId]!!
        val textId = root.childIds[0]
        val buttonId = root.childIds[1]
        val rowId = root.childIds[2]

        assertEquals(WidgetType.TEXT, tree.nodes[textId]!!.type)
        assertEquals("Hello", tree.nodes[textId]!!.properties["value"])

        assertEquals(WidgetType.BUTTON, tree.nodes[buttonId]!!.type)
        assertEquals("Click me", tree.nodes[buttonId]!!.properties["label"])
        assertEquals("click1", tree.nodes[buttonId]!!.properties[PROP_ON_CLICK_EVENT])

        val rowNode = tree.nodes[rowId]!!
        assertEquals(WidgetType.ROW, rowNode.type)
        assertEquals(2, rowNode.childIds.size)

        assertEquals(WidgetType.ICON, tree.nodes[rowNode.childIds[0]]!!.type)
        assertEquals(WidgetType.TEXT, tree.nodes[rowNode.childIds[1]]!!.type)
        assertEquals("World", tree.nodes[rowNode.childIds[1]]!!.properties["value"])
    }

    @Test
    fun `build tree with all leaf widget types`() {
        val tree =
            widgetTree {
                column {
                    text("label")
                    icon("home")
                    button("OK", "ok_event")
                    textField("", "change_event", "placeholder")
                    checkbox(true, "toggle_event", "Accept")
                    dropdown("opt1", listOf("opt1", "opt2"), "select_event")
                    progress(0.5f, false)
                    spacer(16)
                    divider()
                    list(listOf("a", "b", "c"))
                }
            }

        // 1 column + 10 leaf nodes = 11
        assertEquals(11, tree.nodes.size)
        val root = tree.nodes[tree.rootId]!!
        assertEquals(10, root.childIds.size)
    }

    @Test
    fun `scroll container wraps children`() {
        val tree =
            widgetTree {
                scroll {
                    text("item1")
                    text("item2")
                }
            }

        assertEquals(3, tree.nodes.size)
        val root = tree.nodes[tree.rootId]!!
        assertEquals(WidgetType.SCROLL, root.type)
        assertEquals(2, root.childIds.size)
    }

    // ---- Node identity (issue #34 item 6) ----

    private fun sampleTree(label: String = "Hello"): WidgetTree =
        widgetTree {
            column {
                text(label)
                textField("", "name_changed", "Name")
                row {
                    icon("star", 24)
                    button("Save", "save")
                }
            }
        }

    @Test
    fun `ids are deterministic in creation order`() {
        val tree = sampleTree()

        assertEquals("w0", tree.rootId)
        assertEquals(listOf("w1", "w2", "w3"), tree.nodes["w0"]!!.childIds)
        assertEquals(listOf("w4", "w5"), tree.nodes["w3"]!!.childIds)
        assertEquals(setOf("w0", "w1", "w2", "w3", "w4", "w5"), tree.nodes.keys)
    }

    @Test
    fun `rebuilding the same shape reuses the same ids`() {
        val first = sampleTree()
        val second = sampleTree()

        assertEquals(first.rootId, second.rootId)
        assertEquals(first.nodes.keys, second.nodes.keys)
        // Structurally identical rebuild ⇒ nothing to send, and every host-side per-node state
        // (text-field buffers, scroll offsets) keyed by node id stays valid.
        assertTrue(
            WidgetDiffEngine.diff(first, second).isEmpty(),
            "Expected an empty diff for an identical rebuild, got: ${WidgetDiffEngine.diff(first, second)}",
        )
    }

    @Test
    fun `rebuilding with changed values keeps ids and diffs to just the value`() {
        val before = sampleTree("Hello")
        val after = sampleTree("Goodbye")

        val ops = WidgetDiffEngine.diff(before, after)
        assertEquals(1, ops.size, "Expected a single property update, got: $ops")
        val updated = assertIs<DiffOperation.NodeUpdated>(ops.single())
        assertEquals("w1", updated.nodeId)
        assertEquals("Goodbye", updated.changedProperties["value"])
    }

    @Test
    fun `separate builders do not share the id counter`() {
        val a = widgetTree { text("a") }
        val b = widgetTree { text("b") }

        assertEquals("w0", a.rootId)
        assertEquals("w0", b.rootId)
    }

    // ---- Click event ids (issue #34 item 1) ----

    @Test
    fun `button writes both event id spellings`() {
        val tree = widgetTree { button("Save", "save_event") }
        val button = tree.nodes[tree.rootId]!!

        assertEquals("save_event", button.properties[PROP_CLICK_EVENT_ID])
        assertEquals("save_event", button.properties[PROP_ON_CLICK_EVENT])
        assertEquals("save_event", button.resolveClickEventId())
    }
}
