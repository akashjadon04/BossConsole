package ai.rever.boss.components.plugin.remote

import ai.rever.boss.kernel.ui.RemoteUiSurface
import ai.rever.boss.kernel.ui.RemoteUiSurfaceRegistry
import ai.rever.boss.kernel.ui.SurfaceRegistration
import ai.rever.boss.kernel.ui.SurfaceStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * How the two surface components bind themselves to the transport, observed through their public state.
 *
 * Tree delivery and event tagging are asserted end to end with a real composition in
 * [RemoteWidgetRendererComposeTest] — a rendered widget is the only honest evidence that a tree arrived,
 * and a real click the only honest evidence that an event left with the right `surface_id`. What is left
 * for here is the binding itself: that each component follows *its own* surface, that `dispose()` actually
 * unbinds, and that neither hears about the other's plugin.
 */
class RemoteSurfaceComponentTest {
    private val registry = RemoteUiSurfaceRegistry()

    @Test
    fun `a panel follows its own surface's connection state`() {
        val panel = RemotePanelComponent(PANEL, "Inbox", PROCESS, registry)

        panel.attach()
        assertFalse(panel.connected.value, "no plugin has claimed the surface yet")

        registry.register(PANEL, PROCESS).accepted()
        assertFalse(panel.connected.value, "registered is not streaming")

        assertIs<SurfaceStream.Bound>(registry.openStream(PANEL))
        assertTrue(panel.connected.value)

        assertTrue(registry.unregister(PANEL))
        assertFalse(panel.connected.value, "a surface whose plugin left reads as disconnected")
    }

    @Test
    fun `a disposed panel stops hearing about its surface`() {
        val panel = RemotePanelComponent(PANEL, "Inbox", PROCESS, registry)
        panel.attach()

        panel.dispose()

        registry.register(PANEL, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(PANEL))
        assertFalse(panel.connected.value, "dispose must detach, not merely stop rendering")
    }

    @Test
    fun `a panel ignores a plugin that streams a different surface`() {
        val panel = RemotePanelComponent(PANEL, "Inbox", PROCESS, registry)
        panel.attach()

        registry.register(TAB, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(TAB))

        assertFalse(panel.connected.value)
    }

    @Test
    fun `a tab follows its own surface and keeps its own title and loading state`() {
        val tab = RemoteTabComponent(TAB, "Notebook", PROCESS, registry)
        tab.attach()
        assertEquals("Notebook", tab.title.value, "the title seeds from the display name")

        registry.register(TAB, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(TAB))
        tab.updateTitle("Notebook - running")
        tab.setLoading(true)

        assertTrue(tab.connected.value)
        assertEquals("Notebook - running", tab.title.value)
        assertTrue(tab.isLoading.value)
    }

    @Test
    fun `a disposed tab stops hearing about its surface`() {
        val tab = RemoteTabComponent(TAB, "Notebook", PROCESS, registry)
        tab.attach()

        tab.dispose()

        registry.register(TAB, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(TAB))
        assertFalse(tab.connected.value)
    }

    @Test
    fun `a component attached to an already-streaming surface picks up the connection immediately`() {
        registry.register(PANEL, PROCESS).accepted()
        assertIs<SurfaceStream.Bound>(registry.openStream(PANEL))

        val panel = RemotePanelComponent(PANEL, "Inbox", PROCESS, registry)
        panel.attach()

        assertTrue(panel.connected.value, "the surface was already live when the panel opened")
    }

    private fun SurfaceRegistration.accepted(): RemoteUiSurface = assertIs<SurfaceRegistration.Accepted>(this).surface

    private companion object {
        const val PANEL = "panel-1"
        const val TAB = "tab-1"
        const val PROCESS = "plugin-a"
    }
}
