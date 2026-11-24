-- ============================================================================
-- BOSS Database Schema: let an organisation's admins set a plugin's visibility
--
-- File: 20260813000000_set_plugin_visibility.sql
--
-- `plugins.visibility` has existed since 20260803000000 with three values, and
-- nothing has ever written it. Every row is 'public' because that is the column
-- default, and there was no path to change one: the RLS UPDATE policy permits the
-- author or an organisation admin, but every client reaches `plugins` through the
-- plugin-store edge function, which runs as SERVICE ROLE and so bypasses that
-- policy entirely. A policy nothing evaluates is not a control.
--
-- This is the write path, gated explicitly, for the plugin page to call.
--
-- WHO: an admin of the organisation that OWNS the plugin, or a global plugins
-- admin. Deliberately NOT the author. `plugins.author_id` is whoever ran the
-- publish - for most of this store that is one person, and 20260812000000 moved
-- five plugins to an organisation without changing it. Authorship records who
-- pushed the button; the organisation records who is answerable for it, and
-- visibility is an answerable-for decision. It is also the rule the UPDATE policy
-- already states for the org arm, so this does not invent a second one.
--
-- WHAT IT REFUSES, and why each matters:
--
--   * A plugin whose org_id is NULL. `user_is_org_admin(user, NULL)` is false, so
--     this would refuse anyway - but refusing by name says the plugin needs
--     attributing first rather than leaving the caller to read false as "you are
--     not an admin".
--   * A value outside the CHECK. The constraint would raise 23514, which arrives
--     at the caller as an unreadable constraint violation instead of a sentence.
--     Same reason submit_organisation_request validates ahead of its columns.
--   * Making a plugin 'org' or 'unlisted' is ALLOWED and is the point. Note what
--     it costs the reader: user_can_view_plugin_row short-circuits on
--     public+published, so anything else means anonymous callers - including the
--     Toolbox's own catalogue read, which runs as `anon` - stop seeing it. That is
--     the intended effect and it is worth knowing before pressing it.
-- ============================================================================


CREATE OR REPLACE FUNCTION "public"."set_plugin_visibility"(
    "p_plugin_id" "uuid",
    "p_visibility" "text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor  UUID;
    v_org_id UUID;
    v_old    TEXT;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF p_visibility IS NULL OR p_visibility NOT IN ('public', 'org', 'unlisted') THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Visibility must be public, org or unlisted');
    END IF;

    -- FOR UPDATE so two administrators pressing different values at the same moment
    -- serialise rather than interleaving read and write. The last one wins, which is
    -- the same answer either order - but the row each of them REPORTS having changed
    -- is then the one they actually changed.
    SELECT p.org_id, p.visibility
      INTO v_org_id, v_old
      FROM public.plugins p
     WHERE p.id = p_plugin_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Plugin not found');
    END IF;

    IF v_org_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error',
            'This plugin belongs to no organisation, so no organisation can set its visibility');
    END IF;

    -- The org arm of the UPDATE policy, stated once here because service role never
    -- evaluates that policy. is_user_admin is the global-admin escape the rest of the
    -- store already honours.
    IF NOT (public.is_user_admin(v_actor) OR public.user_is_org_admin(v_actor, v_org_id)) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    UPDATE public.plugins
       SET visibility = p_visibility,
           updated_at = now()
     WHERE id = p_plugin_id;

    RETURN jsonb_build_object(
        'success', true,
        'visibility', p_visibility,
        -- Returned so a caller can tell a real change from a no-op press without
        -- reading the row again.
        'changed', v_old IS DISTINCT FROM p_visibility);
END;
$$;

ALTER FUNCTION "public"."set_plugin_visibility"("uuid", "text", "uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."set_plugin_visibility"("uuid", "text", "uuid")
    FROM PUBLIC, "anon";
GRANT  EXECUTE ON FUNCTION "public"."set_plugin_visibility"("uuid", "text", "uuid")
    TO "authenticated", "service_role";

COMMENT ON FUNCTION "public"."set_plugin_visibility"("uuid", "text", "uuid") IS
    'Sets plugins.visibility for an admin of the owning organisation, or a global plugins admin. The RLS UPDATE policy states the same rule, but every client reaches plugins through the service-role plugin-store function, which bypasses it - so this is where the rule is actually enforced. Refuses a plugin with no organisation by name rather than as a permission failure.';


-- ============================================================================
-- The organisation's plugins, for the section that links to each plugin's page
--
-- `organisations.plugin_count` has been on the org page since 20260803000000, so
-- a reader is already told a number with nothing behind it. This is the list.
--
-- WHY AN RPC RATHER THAN A SELECT IN THE EDGE FUNCTION: the organisation function
-- is a SERVICE ROLE caller, so `.from("plugins").select().eq("org_id", ...)` would
-- put an authorization decision in TypeScript, outside every test the database
-- layer has, and bypass RLS while doing it. utils/org-rpc.ts states that rule; this
-- is what keeps this feature inside it.
--
-- EACH ROW IS FILTERED BY user_can_view_plugin_row FOR THE ACTOR, not merely by
-- org_id. Membership of an organisation is not by itself permission to see every
-- plugin attached to it - `unlisted` means unlisted to members too - and reusing
-- the same predicate the store reads through means this list cannot drift into
-- showing more than the catalogue would.
--
-- No paging. An organisation with more plugins than one page is not a shape this
-- store has (the largest today is 35), and a LIMIT nobody can page past would
-- silently truncate the list instead. Revisit with a real page control, not a cap.
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."list_org_plugins"(
    "p_org_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Only a member may enumerate. Without this the function answers for any org id
    -- to any signed-in caller, which turns "which plugins does that company own" into
    -- a public question the store's own visibility rules were written to scope.
    IF NOT (public.is_user_admin(v_actor) OR public.user_is_org_member(v_actor, p_org_id)) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'plugins', COALESCE((
            SELECT jsonb_agg(row_to_json(r)::jsonb ORDER BY r.display_name)
              FROM (
                SELECT p.plugin_id,
                       p.display_name,
                       p.description,
                       p.icon_url,
                       p.visibility,
                       p.published,
                       p.verified
                  FROM public.plugins p
                 WHERE p.org_id = p_org_id
                   AND public.user_can_view_plugin_row(
                           v_actor, p.visibility, p.org_id, p.author_id, p.published)
              ) r
        ), '[]'::jsonb));
END;
$$;

ALTER FUNCTION "public"."list_org_plugins"("uuid", "uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."list_org_plugins"("uuid", "uuid") FROM PUBLIC, "anon";
GRANT  EXECUTE ON FUNCTION "public"."list_org_plugins"("uuid", "uuid")
    TO "authenticated", "service_role";

COMMENT ON FUNCTION "public"."list_org_plugins"("uuid", "uuid") IS
    'The plugins owned by one organisation, for its members, each row filtered by user_can_view_plugin_row for the actor so this list can never show more than the store catalogue would. Enumeration requires membership: without that check, any signed-in caller could ask which plugins any organisation owns.';
