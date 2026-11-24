package ai.rever.boss.ui.sdk

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the property/modifier interpretation rules every renderer must agree on.
 *
 * These are the rules the Rust renderer port (BossConsoleRust `crates/boss-remote-ui`) disagreed
 * with the JVM stack about — issue #34 items 1, 3, 4 and 5.
 */
class WidgetSemanticsTest {
    private fun button(
        properties: Map<String, String>,
        modifier: WidgetModifier = WidgetModifier(),
    ) = WidgetNode("n1", WidgetType.BUTTON, properties = properties, modifier = modifier)

    // ---- Item 1: click event id ----

    @Test
    fun `canonical spelling wins`() {
        val node =
            button(
                mapOf(PROP_CLICK_EVENT_ID to "canonical", PROP_ON_CLICK_EVENT to "legacy"),
                WidgetModifier(clickEventId = "modifier"),
            )
        assertEquals("canonical", node.resolveClickEventId())
    }

    @Test
    fun `builder spelling is accepted when the canonical one is absent`() {
        assertEquals("legacy", button(mapOf(PROP_ON_CLICK_EVENT to "legacy")).resolveClickEventId())
    }

    @Test
    fun `empty values fall through to the next source`() {
        val node =
            button(
                mapOf(PROP_CLICK_EVENT_ID to "", PROP_ON_CLICK_EVENT to ""),
                WidgetModifier(clickEventId = "modifier"),
            )
        assertEquals("modifier", node.resolveClickEventId())
    }

    @Test
    fun `no event id anywhere resolves to empty`() {
        assertEquals("", button(mapOf("label" to "Go")).resolveClickEventId())
    }

    // ---- Item 3: list items ----

    @Test
    fun `list items split on commas`() {
        val node = WidgetNode("n1", WidgetType.LIST, properties = mapOf(PROP_ITEMS to "a,b,c"))
        assertEquals(listOf("a", "b", "c"), node.resolveListItems())
    }

    @Test
    fun `blank or absent items yield no rows`() {
        assertEquals(emptyList(), WidgetNode("n1", WidgetType.LIST).resolveListItems())
        assertEquals(
            emptyList(),
            WidgetNode("n1", WidgetType.LIST, properties = mapOf(PROP_ITEMS to "")).resolveListItems(),
        )
    }

    @Test
    fun `blank options yield no dropdown entries`() {
        // Naive `split(",")` would produce a single empty option here.
        val node = WidgetNode("n1", WidgetType.DROPDOWN, properties = mapOf(PROP_OPTIONS to ""))
        assertEquals(emptyList(), node.resolveDropdownOptions())
        assertEquals(
            listOf("one", "two"),
            WidgetNode("n1", WidgetType.DROPDOWN, properties = mapOf(PROP_OPTIONS to "one,two"))
                .resolveDropdownOptions(),
        )
    }

    // ---- Item 4: alpha ----

    @Test
    fun `alpha inside the open unit interval is honoured`() {
        assertEquals(0.5f, WidgetModifier(alpha = 0.5f).effectiveAlpha())
        assertEquals(0.01f, WidgetModifier(alpha = 0.01f).effectiveAlpha())
    }

    @Test
    fun `proto3 default alpha means unset, not invisible`() {
        // The whole point: an out-of-process plugin that never touches `alpha` sends 0.0, and
        // honouring that literally would make every widget it renders disappear.
        assertNull(WidgetModifier(alpha = 0f).effectiveAlpha())
        assertNull(WidgetModifier(alpha = -1f).effectiveAlpha())
    }

    @Test
    fun `opaque and out-of-range alphas need no compositing`() {
        assertNull(WidgetModifier().effectiveAlpha())
        assertNull(WidgetModifier(alpha = 1f).effectiveAlpha())
        assertNull(WidgetModifier(alpha = 2f).effectiveAlpha())
        assertNull(WidgetModifier(alpha = Float.NaN).effectiveAlpha())
    }

    @Test
    fun `wire alpha canonicalizes everything that composites nothing to opaque`() {
        // So that structural equality means semantic equality — WidgetDiffEngine compares whole
        // modifiers, and the wire's unset 0f vs the Kotlin default 1f otherwise look like a change.
        assertEquals(1f, normalizeWireAlpha(0f))
        assertEquals(1f, normalizeWireAlpha(-1f))
        assertEquals(1f, normalizeWireAlpha(1f))
        assertEquals(1f, normalizeWireAlpha(2f))
        assertEquals(1f, normalizeWireAlpha(Float.NaN))
        // Real values pass through untouched.
        assertEquals(0.25f, normalizeWireAlpha(0.25f))
    }

    @Test
    fun `canonicalized alphas agree with the sentinel`() {
        for (raw in listOf(-1f, 0f, 0.5f, 1f, 2f, Float.NaN)) {
            assertEquals(
                WidgetModifier(alpha = raw).effectiveAlpha(),
                WidgetModifier(alpha = normalizeWireAlpha(raw)).effectiveAlpha(),
                "normalizing must not change what gets composited (raw=$raw)",
            )
        }
    }

    // ---- Item 5: background color ----

    @Test
    fun `theme tokens resolve`() {
        assertEquals(BackgroundSpec.Token(ThemeToken.PANEL), parseBackgroundColor("panel"))
        assertEquals(BackgroundSpec.Token(ThemeToken.SIGNAL_WASH), parseBackgroundColor("signalWash"))
        assertEquals(BackgroundSpec.Token(ThemeToken.SIGNAL_WASH), parseBackgroundColor("signal_wash"))
        assertEquals(BackgroundSpec.Token(ThemeToken.SIGNAL_WASH), parseBackgroundColor("SIGNAL-WASH"))
        assertEquals(BackgroundSpec.Token(ThemeToken.ON_SIGNAL), parseBackgroundColor("onSignal"))
        assertEquals(BackgroundSpec.Token(ThemeToken.SIGNAL_TEXT), parseBackgroundColor("signalText"))
        assertEquals(BackgroundSpec.Token(ThemeToken.SIGNAL_TEXT), parseBackgroundColor("signal_text"))
    }

    @Test
    fun `every design-system token is addressable by name`() {
        for (token in ThemeToken.entries) {
            assertEquals(BackgroundSpec.Token(token), parseBackgroundColor(token.tokenName))
        }
        // Deliberate literal: this module cannot see BossColorScheme (that reflective
        // check lives in composeApp's RemoteWidgetRendererColorTest), so the count is
        // what makes adding a scheme field without a wire token fail here.
        assertEquals(18, ThemeToken.entries.size)
    }

    @Test
    fun `hex forms parse to packed argb`() {
        assertEquals(BackgroundSpec.Hex(0xFFFF0000), parseBackgroundColor("#FF0000"))
        assertEquals(BackgroundSpec.Hex(0xFFFF0000), parseBackgroundColor("FF0000"))
        assertEquals(BackgroundSpec.Hex(0x80FF0000), parseBackgroundColor("#80FF0000"))
        assertEquals(BackgroundSpec.Hex(0xFF00FF00), parseBackgroundColor("#00ff00"))
    }

    @Test
    fun `unparseable specs draw no background instead of throwing`() {
        assertEquals(BackgroundSpec.None, parseBackgroundColor(""))
        // Three-digit shorthand has never been supported by this protocol.
        assertEquals(BackgroundSpec.None, parseBackgroundColor("#F00"))
        assertEquals(BackgroundSpec.None, parseBackgroundColor("#GGGGGG"))
        assertEquals(BackgroundSpec.None, parseBackgroundColor("rebeccapurple"))
        assertEquals(BackgroundSpec.None, parseBackgroundColor("#"))
    }

    @Test
    fun `modifier shorthand delegates to the parser`() {
        assertEquals(
            BackgroundSpec.Token(ThemeToken.RAISED),
            WidgetModifier(backgroundColor = "raised").resolveBackground(),
        )
        assertEquals(BackgroundSpec.None, WidgetModifier().resolveBackground())
    }
}
