/**
 * One plugin, for its page.
 *
 * Read through `get_plugin_with_stats_for_viewer`, which the plugin-store migrations already own,
 * rather than selecting from `plugins` here. Two reasons, and the second is the important one:
 * that RPC applies `user_can_view_plugin_row` for the viewer, so an `org` or `unlisted` plugin is
 * invisible to somebody who may not see it; and this function is a SERVICE ROLE caller, so a
 * `.from("plugins").select()` would be an authorization decision written in TypeScript, outside
 * every test the database layer has. Same rule as utils/org-rpc.ts states for the org tables.
 */

import { callForActor, callRpcRows } from "../utils/org-rpc.ts"

export interface PluginDetail {
  id: string
  plugin_id: string
  display_name: string
  description: string | null
  author_name: string | null
  homepage_url: string | null
  icon_url: string | null
  type: string | null
  api_version: string | null
  verified: boolean
  published: boolean
  visibility: string
  org_id: string | null
  org_slug: string | null
  download_count: number | null
  latest_version: string | null
  updated_at: string | null
}

/**
 * The plugin, or null when this viewer may not see it or it does not exist.
 *
 * ONE NULL FOR BOTH, deliberately. Distinguishing "no such plugin" from "you may not see this
 * one" tells an unauthorised reader that a private plugin with that id exists, which is most of
 * what they wanted to know. The page renders the same not-found either way.
 */
export async function loadPlugin(
  pluginId: string,
  /** Null for a signed-out reader, which the RPC answers with its anonymous arm: public+published. */
  viewerId: string | null,
): Promise<PluginDetail | null> {
  // callRpcRows, NOT callRpc. This is the one set-returning function this edge function calls:
  // it is declared RETURNS TABLE, so PostgREST answers with a bare array of rows and there is no
  // `success` envelope for callRpc to find. Reading it through callRpc returned ok:false for every
  // plugin, so the page 404'd for everybody while every test still passed. See utils/org-rpc.ts.
  const result = await callRpcRows<Record<string, unknown>>("get_plugin_with_stats_for_viewer", {
    p_plugin_id: pluginId,
    p_viewer_id: viewerId,
  })
  if (!result.ok) return null

  // No rows is the not-visible and the not-found case alike, which is what this function's null
  // deliberately conflates.
  const row = result.data[0]
  if (!row || typeof row.id !== "string") return null

  return {
    id: row.id,
    plugin_id: String(row.plugin_id ?? ""),
    display_name: String(row.display_name ?? ""),
    description: (row.description as string) ?? null,
    author_name: (row.author_name as string) ?? null,
    homepage_url: (row.homepage_url as string) ?? null,
    icon_url: (row.icon_url as string) ?? null,
    type: (row.type as string) ?? null,
    api_version: (row.api_version as string) ?? null,
    verified: row.verified === true,
    published: row.published === true,
    // Defaulted rather than trusted: the column is NOT NULL, but this page renders a control
    // keyed on the value and an unexpected one must not select every radio at once.
    visibility: typeof row.visibility === "string" ? row.visibility : "public",
    org_id: (row.org_id as string) ?? null,
    org_slug: (row.org_slug as string) ?? null,
    download_count: typeof row.download_count === "number" ? row.download_count : null,
    latest_version: (row.latest_version as string) ?? null,
    updated_at: (row.updated_at as string) ?? null,
  }
}

/** One row of the organisation's plugin list. A subset of PluginDetail: the list links to the page. */
export interface OrgPluginSummary {
  plugin_id: string
  display_name: string
  description: string | null
  icon_url: string | null
  visibility: string
  published: boolean
  verified: boolean
}

/**
 * The organisation's plugins.
 *
 * An empty list on every failure, deliberately, and this is the one place it costs something: a
 * refused call and an organisation with no plugins render identically. The alternative is a page
 * that fails to load because GitHub, or a nested RPC, had a bad minute - and this section sits
 * below Members and Roles on a page whose job is those two. `plugin_count` on the same page still
 * shows the real number, so a list that is empty against a non-zero count is legible as a fault
 * rather than silently wrong.
 */
export async function listOrgPlugins(
  actorId: string,
  orgId: string,
): Promise<OrgPluginSummary[]> {
  const result = await callForActor<unknown>("list_org_plugins", actorId, {
    p_org_id: orgId,
  })
  if (!result.ok) return []

  // callRpc already unwrapped the envelope's `data`, so this IS the row array. It used to be
  // reached as `envelope.plugins`, a key invented for this one function; 20260815000000 renamed it
  // to `data` so the shared decoders on both clients can read it.
  const rows = result.data
  if (!Array.isArray(rows)) return []

  return rows.flatMap((raw) => {
    const row = raw as Record<string, unknown>
    // The id is what the link is built from, so a row without one is dropped rather than rendered
    // as a link to nowhere.
    if (typeof row.plugin_id !== "string" || row.plugin_id === "") return []
    return [{
      plugin_id: row.plugin_id,
      display_name: typeof row.display_name === "string" ? row.display_name : row.plugin_id,
      description: (row.description as string) ?? null,
      icon_url: (row.icon_url as string) ?? null,
      visibility: typeof row.visibility === "string" ? row.visibility : "public",
      published: row.published === true,
      verified: row.verified === true,
    }]
  })
}
