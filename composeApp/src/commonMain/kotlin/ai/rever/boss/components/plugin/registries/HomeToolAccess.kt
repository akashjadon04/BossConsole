package ai.rever.boss.components.plugin.registries

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The current user's RBAC snapshot, for the home screen's tool grid.
 *
 * A member of [AccessGatedRegistry.ACCESS_GATED] rather than a new read on
 * `DynamicPluginManager`, whose `_isAdmin` and `_userPermissions` are private. Joining that list
 * is the supported way to receive this: the manager's single access collector pushes to every
 * member in one loop, so the grid's view of who the user is cannot drift from the one
 * `PanelMenuRegistryImpl`, `SettingsPageRegistryImpl` and `StatusBarRegistryImpl` use, and a
 * change of user re-filters the grid live.
 *
 * Holds no contributions of its own - it gates a list built somewhere else - so it is a mirror
 * rather than a registry. Being in the list is still right: what the list actually means is
 * "everything that needs the access snapshot".
 */
object HomeToolAccess : AccessGatedRegistry {
    private val _access = MutableStateFlow(RegistryAccess())

    /** Observed by the home screen so gated tools appear and disappear live. */
    val access: StateFlow<RegistryAccess> = _access.asStateFlow()

    override fun updateAccess(
        isAdmin: Boolean,
        permissions: Set<String>,
    ) {
        _access.value = RegistryAccess(isAdmin, permissions)
    }
}
