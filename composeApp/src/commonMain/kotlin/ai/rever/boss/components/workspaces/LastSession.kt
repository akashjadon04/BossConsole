package ai.rever.boss.components.workspaces

/** Workspace id of the automatically maintained "Last Session" layout. */
const val LAST_SESSION_ID = "last-session"

/** Workspace name of the automatically maintained "Last Session" layout. */
const val LAST_SESSION_NAME = "Last Session"

private const val LAST_SESSION_DESCRIPTION = "Automatically saved session"

/**
 * Stamp [layout] with the "Last Session" identity.
 *
 * "Last Session" is a single app-level workspace, not a per-window one: exactly
 * one writer may produce it at a time, or windows overwrite each other (Issue #19).
 */
fun asLastSession(layout: LayoutWorkspace): LayoutWorkspace =
    layout.copy(
        id = LAST_SESSION_ID,
        name = LAST_SESSION_NAME,
        description = LAST_SESSION_DESCRIPTION,
    )
