-- ============================================================================
-- BOSS Database Schema: list_org_plugins returns `data`, like every other org RPC
--
-- File: 20260815000000_list_org_plugins_data_key.sql
--
-- 20260813000000 returned its rows under a key called `plugins`. Every other
-- organisation RPC returns `{"success": true, "data": [...]}`, and both clients say
-- so in their own words: utils/org-rpc.ts opens with "Every org RPC follows one
-- shape", and the desktop plugin's RpcEnvelope calls that shape "the normal case".
--
-- The edge function was written against the odd key and so never noticed. The
-- desktop plugin uses the shared decoder, which found `success: true` and no `data`
-- and reported "Something went wrong. Please try again." - a plugins tab that could
-- never load, on a page where the failure was indistinguishable from the RPC being
-- unreachable.
--
-- WHY RENAME RATHER THAN TEACH THE DECODER A SECOND SHAPE: the convention is the
-- thing that lets a new client call any of these functions without reading each one.
-- One exception costs every future caller a lookup, and the exception here was not
-- deliberate - it was a key invented while writing the first consumer.
--
-- SAFE TO CHANGE: the only two callers are this repo's organisation edge function,
-- updated in the same commit, and the organisation plugin, which has always expected
-- `data`. Nothing else has ever read it.
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
        -- `data`, not `plugins`. See the header: the shared decoders key on this.
        'data', COALESCE((
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

COMMENT ON FUNCTION "public"."list_org_plugins"("uuid", "uuid") IS
    'The plugins owned by one organisation, for its members, each row filtered by user_can_view_plugin_row for the actor so this list can never show more than the store catalogue would. Enumeration requires membership. Returns the standard org envelope {success, data} - 20260813000000 returned the rows under `plugins`, which the shared client decoders do not read.';
