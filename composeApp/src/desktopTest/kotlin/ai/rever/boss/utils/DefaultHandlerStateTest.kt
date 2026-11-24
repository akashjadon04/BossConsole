package ai.rever.boss.utils

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [DefaultHandlerState], the three-way answer that replaced a boolean.
 *
 * The [DefaultHandlerState.OurEngine] case is the one this type exists for. On
 * every machine that installed BOSS before `build-chromium-branding.yml` learned
 * to strip them, `~/.boss/boss-chromium/BOSS.app` declares http, https and
 * `public.html` and is also called "BOSS" - so System Settings offered two
 * indistinguishable entries and the default browser is very likely the engine.
 * The old boolean answered "not the default", which told a user who had set it
 * that they had not.
 */
class DefaultHandlerStateTest {
    @Test
    fun `the app itself is ours`() {
        assertEquals(DefaultHandlerState.Ours, DefaultHandlerState.of("ai.rever.boss"))
        assertTrue(DefaultHandlerState.of("ai.rever.boss").isOurs)
    }

    @Test
    fun `the chromium engine bundle is recognised as ours but not as us`() {
        val state = DefaultHandlerState.of("ai.rever.boss.browser")
        assertEquals(DefaultHandlerState.OurEngine, state)
        // Crucially NOT ours: the whole point is that this state needs a repair,
        // and reporting it as ours would leave the user with links that open a
        // bare Chromium and a Settings screen saying everything is fine.
        assertFalse(state.isOurs)
    }

    @Test
    fun `another application is other, and names itself`() {
        val state = DefaultHandlerState.of("com.apple.Safari")
        assertEquals(DefaultHandlerState.Other("com.apple.Safari"), state)
        assertFalse(state.isOurs)
    }

    @Test
    fun `nothing registered is other with no name`() {
        assertEquals(DefaultHandlerState.Other(null), DefaultHandlerState.of(null))
    }

    @Test
    fun `bundle ids are compared case-insensitively`() {
        // Launch Services returns the spelling from the app's own Info.plist,
        // which need not match the constant's case. A case-sensitive comparison
        // would report BOSS as some other app.
        assertEquals(DefaultHandlerState.Ours, DefaultHandlerState.of("AI.Rever.Boss"))
        assertEquals(DefaultHandlerState.OurEngine, DefaultHandlerState.of("AI.REVER.BOSS.BROWSER"))
    }

    @Test
    fun `the engine id is not confused with the app id by prefix`() {
        // `ai.rever.boss.browser` starts with `ai.rever.boss`. A `startsWith`
        // comparison anywhere in this chain would call the engine "ours" and
        // silently remove the repair.
        assertEquals(DefaultHandlerState.OurEngine, DefaultHandlerState.of("ai.rever.boss.browser"))
        val sibling = "ai.rever.boss.something"
        assertEquals(DefaultHandlerState.Other(sibling), DefaultHandlerState.of(sibling))
    }

    @Test
    fun `the worst answer wins when types in a category disagree`() {
        val reduce = DefaultHandlerState::reduce

        assertEquals(
            DefaultHandlerState.Ours,
            reduce(listOf(DefaultHandlerState.Ours, DefaultHandlerState.Ours)),
        )
        // OurEngine outranks Other, because it is the answer with a one-click
        // fix and stays true whether one type or all of them are affected.
        assertEquals(
            DefaultHandlerState.OurEngine,
            reduce(
                listOf(
                    DefaultHandlerState.Ours,
                    DefaultHandlerState.Other("com.apple.Safari"),
                    DefaultHandlerState.OurEngine,
                ),
            ),
        )
        assertEquals(
            DefaultHandlerState.Other("com.apple.Safari"),
            reduce(listOf(DefaultHandlerState.Ours, DefaultHandlerState.Other("com.apple.Safari"))),
        )
        // Nothing to reduce is not "everything is fine".
        assertEquals(DefaultHandlerState.Other(null), reduce(emptyList()))
    }
}
