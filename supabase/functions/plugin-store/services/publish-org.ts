/**
 * Which organisation a newly published plugin belongs to.
 *
 * Until now: always the boss organisation. `createPlugin` never set `org_id`, so the
 * `plugins_default_org` BEFORE INSERT trigger resolved `slug = 'boss'` for every row. The
 * organisation was never CHOSEN - not from the request, not from a JWT claim, and not even from
 * the publishing API key's own `org_id`, which `validate_plugin_api_key` returns and this function
 * used to discard. Every plugin in the store is boss-owned as a result, which makes ownership
 * inert: `@boss` is the system organisation every signup joins, so it restricts nothing.
 *
 * THE PUBLISH PATH RUNS AS SERVICE ROLE, so RLS is bypassed - including the
 * `can_publish_org_plugin` WITH CHECK on `plugins`. Nothing else would stop a caller naming an
 * organisation they have no rights in. The gate therefore has to be explicit here, which is what
 * `validate_plugin_api_key`'s own COMMENT already specifies: "The publish path must gate on
 * user_can_publish_org_plugin(user_id, org_id)".
 *
 * `user_can_publish_org_plugin` is that single source of truth - it evaluates `publish_role_id`
 * (which overrides) then `publish_policy`, and short-circuits true for a global admin. It is
 * deliberately not reimplemented in TypeScript.
 *
 * This module answers "which organisation, and may you publish for it". Whether you may publish
 * AT ALL is services/publish-authz.ts, which layers the `plugins.create` / org-scoped rule on top.
 */

import type { SupabaseClient } from "@supabase/supabase-js"

/**
 * The minimum this needs to know about the caller.
 *
 * Structural rather than `AuthResult`, because two different authentication paths reach here with
 * two different shapes: the publish routes carry an `AuthResult`, and the API-key routes carry
 * `getUserFromToken`'s narrower object. Both satisfy this, and neither has to be widened to a type
 * it does not otherwise use.
 *
 * `apiKeyOrgId` is absent on a browser publish, which is exactly right: there is no key to inherit
 * an organisation from.
 */
export interface PublishingCaller {
  userId: string
  apiKeyOrgId?: string
}

/**
 * How the organisation was arrived at.
 *
 * Carried because `orgId: null` means three different things and the org-scoped gate in
 * publish-authz.ts has to tell them apart to refuse honestly: "you named nothing and have no single
 * organisation to fall back on" and "your organisation's publish policy refuses you" send the
 * reader to completely different places. Nothing in the pre-existing behaviour reads it.
 */
export type PublishOrgSource =
  /** The caller named it, or their API key is bound to it. Authorised. */
  | "explicit"
  /** Their sole non-system organisation, authorised. */
  | "derived"
  /** Nothing named and the caller belongs to no non-system organisation. `orgId` is null. */
  | "no_org"
  /** Nothing named and more than one candidate, so there was nothing to derive. `orgId` is null. */
  | "many_orgs"
  /** A sole candidate existed and its publish policy refused them. `orgId` is null. */
  | "policy_refused"
  /** The membership list could not be read at all. `orgId` is null. */
  | "unreadable"

/** The organisation to record, or a refusal to hand back to the caller. */
export type PublishOrgResolution =
  | { ok: true; orgId: string | null; source: PublishOrgSource }
  | { ok: false; status: 403 | 500; error: string }

/** The one wording for "we could not ask the database", shared by both gates. */
export const ORG_CHECK_UNAVAILABLE =
  "Could not verify your publishing rights for that organisation. Please try again."

/**
 * Decide and authorise the owning organisation for a NEW plugin.
 *
 * Only for creation. A new VERSION of an existing plugin must never re-derive this: ownership is a
 * property of the plugin, and re-resolving on every version would silently move a plugin between
 * organisations as its publisher's memberships changed.
 *
 * Precedence, most explicit first:
 *
 *   1. `requestedOrgId` - the caller said so. Always authorised, never trusted.
 *   2. The API key's bound `org_id`, for a CI publish. Also authorised: the key is not an
 *      independent principal, so a revoked membership must revoke the key's publish rights.
 *   3. The caller's single non-system organisation, when they have exactly one.
 *   4. Null, meaning "let the trigger decide", which is the boss organisation.
 *
 * Step 3 requires EXACTLY ONE for a reason. The original migration rejected deriving from
 * membership as ambiguous, and it is right whenever there is a choice to make: picking the
 * alphabetically-first of three organisations would attribute somebody's plugin to a place they
 * did not intend and give that organisation's admins rights over it. With exactly one candidate
 * there is nothing to guess. Systems organisations are excluded because `@boss` holds every user,
 * so counting it would make the answer "boss" for everybody and defeat the whole step.
 */
export async function resolvePublishOrg(
  supabase: SupabaseClient,
  user: PublishingCaller,
  requestedOrgId?: string | null,
): Promise<PublishOrgResolution> {
  const explicit = requestedOrgId?.trim() || user.apiKeyOrgId?.trim() || null

  if (explicit) {
    const allowed = await canPublishForOrg(supabase, user.userId, explicit)
    if (allowed === null) {
      // Could not determine. Fail closed rather than falling through to the derivation, which
      // would quietly publish somewhere else than the caller asked for.
      // 500, not 503, because 503 is not in these routes' declared response set and widening
      // three OpenAPI definitions to express "transient" is not worth it. The message carries the
      // retry advice, which is the part the caller acts on.
      return { ok: false, status: 500, error: ORG_CHECK_UNAVAILABLE }
    }
    if (!allowed) {
      return {
        ok: false,
        status: 403,
        error: "You do not have permission to publish plugins for that organisation.",
      }
    }
    return { ok: true, orgId: explicit, source: "explicit" }
  }

  const candidates = await nonSystemOrgIds(supabase, user.userId)
  if (candidates === null) return { ok: true, orgId: null, source: "unreadable" }
  if (candidates.length === 0) return { ok: true, orgId: null, source: "no_org" }
  if (candidates.length > 1) return { ok: true, orgId: null, source: "many_orgs" }
  const sole = candidates[0]

  // Authorised too, even though it was derived rather than named: membership alone is not
  // publishing rights. An organisation with publish_policy = 'owner_only' must not have every
  // member's plugins land on it.
  const allowed = await canPublishForOrg(supabase, user.userId, sole)
  if (allowed !== true) return { ok: true, orgId: null, source: "policy_refused" }
  return { ok: true, orgId: sole, source: "derived" }
}

/**
 * `user_can_publish_org_plugin`, or null when the question could not be answered.
 *
 * Null is distinct from false on purpose: false is a decision, null is an outage, and the caller
 * treats them differently for an explicitly requested organisation.
 *
 * Exported for publish-authz.ts, which asks the same question about the organisation a plugin
 * ALREADY belongs to - a path with no resolution to do.
 */
export async function canPublishForOrg(
  supabase: SupabaseClient,
  userId: string,
  orgId: string,
): Promise<boolean | null> {
  const { data, error } = await supabase.rpc("user_can_publish_org_plugin", {
    p_user_id: userId,
    p_org_id: orgId,
  })
  if (error) {
    console.error("user_can_publish_org_plugin failed:", error.message)
    return null
  }
  return data === true
}

/**
 * The caller's ACTIVE, NON-SYSTEM organisations, or null when the list could not be read.
 *
 * Read through `get_my_organisations(p_actor_id)` rather than the membership tables directly.
 * `p_actor_id` is the service_role-only impersonation parameter that exists for exactly this: the
 * function runs as service role, so `auth.uid()` is null and the RPC's default subject would be
 * nobody. Going through the RPC also means the definition of "my organisations" stays in one
 * place instead of being restated as a join here.
 *
 * System organisations are dropped here rather than by each caller, which is what makes this
 * usable as the org-scoped gate's is_system test as well: an id absent from this list is either
 * not the caller's or is `@boss`, and both must refuse. Null (unreadable) is NOT an empty list -
 * the derivation treats it as "derive nothing" while the gate has to fail closed, so the
 * distinction has to survive the return.
 */
export async function nonSystemOrgIds(
  supabase: SupabaseClient,
  userId: string,
): Promise<string[] | null> {
  const { data, error } = await supabase.rpc("get_my_organisations", { p_actor_id: userId })
  if (error) {
    console.error("get_my_organisations failed while resolving a publish organisation:", error.message)
    return null
  }

  const envelope = data as { success?: unknown; data?: unknown } | null
  if (!envelope || envelope.success !== true || !Array.isArray(envelope.data)) return null

  const candidates = (envelope.data as Array<Record<string, unknown>>)
    .filter((row) => row.is_system !== true)
    // An absent status counts as active: get_my_organisations projects it today, but a shape that
    // omitted it for the common case must not read as "not a member".
    .filter((row) => row.status === undefined || row.status === null || row.status === "active")
    .map((row) => (typeof row.id === "string" ? row.id : null))
    .filter((id): id is string => id !== null)

  return [...new Set(candidates)]
}
