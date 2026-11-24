package ai.rever.boss.kernel.services

import ai.rever.boss.ipc.proto.Empty
import ai.rever.boss.ipc.proto.services.ContextMenuActionRequest
import ai.rever.boss.ipc.proto.services.ContextMenuIdRequest
import ai.rever.boss.ipc.proto.services.ContextMenuItemProto
import ai.rever.boss.ipc.proto.services.RegisterContextMenuRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.lang.reflect.Modifier as JavaModifier

/**
 * Pins [ContextMenuServiceBridge]'s acknowledge-and-drop contract (issue #30).
 *
 * The bridge deliberately does nothing with an out-of-process plugin's context menu
 * registrations. The two things a refactor could plausibly regress are covered here:
 * answering `OK` at all (rather than throwing, which reaches the plugin as a gRPC
 * error), and staying stateless (re-growing a registry would resurrect the fiction
 * that something reads it).
 */
class ContextMenuServiceBridgeTest {
    private val bridge = ContextMenuServiceBridge()

    @Test
    fun `registerContextMenu acknowledges without throwing`() =
        runBlocking {
            val item =
                ContextMenuItemProto
                    .newBuilder()
                    .setLabel("Copy")
                    .setActionId("Copy")
                    .build()
            val request =
                RegisterContextMenuRequest
                    .newBuilder()
                    .setContextMenuId("ctx_1")
                    .setNodeId("node_1")
                    .addItems(item)
                    .build()

            assertEquals(Empty.getDefaultInstance(), bridge.registerContextMenu(request))
        }

    @Test
    fun `unregisterContextMenu acknowledges an id that was never registered`() =
        runBlocking {
            val request = ContextMenuIdRequest.newBuilder().setContextMenuId("never_registered").build()

            assertEquals(Empty.getDefaultInstance(), bridge.unregisterContextMenu(request))
        }

    @Test
    fun `onContextMenuAction acknowledges an action with no registration behind it`() =
        runBlocking {
            val request =
                ContextMenuActionRequest
                    .newBuilder()
                    .setContextMenuId("ctx_1")
                    .setActionId("Copy")
                    .build()

            assertEquals(Empty.getDefaultInstance(), bridge.onContextMenuAction(request))
        }

    @Test
    fun `bridge keeps no per-registration state`() {
        val instanceFields =
            ContextMenuServiceBridge::class.java.declaredFields
                .filterNot { it.isSynthetic || JavaModifier.isStatic(it.modifiers) }

        assertTrue(
            instanceFields.isEmpty(),
            "The bridge must stay stateless - nothing in the host reads these registrations. " +
                "Found instance fields: ${instanceFields.map { it.name }}",
        )
    }
}
