-- ============================================================================
-- BOSS Database Schema: Single-organisation detail read
-- ============================================================================
-- File: 20260804000000_organisation_detail_rpc.sql
-- Description:
--   get_organisation_detail(p_org_id, p_actor_id) -- everything the admin
--   configuration page renders about ONE organisation.
--
--   get_my_organisations already returns the org rows a user belongs to, but it
--   deliberately projects only the fields a picker needs. Three settings the
--   admin page has to render round-trip nowhere else:
--
--     publish_role_id           -- which role may publish, overriding publish_policy
--     auto_assign_member_role   -- whether joiners get <slug>_user automatically
--     max_custom_roles          -- the cap the "create role" button must respect
--
--   Without this the edge function would have to SELECT the organisations table
--   directly over its service-role client, which bypasses RLS and would put an
--   authorization decision in TypeScript. Every other org read is a gated RPC;
--   this keeps that property whole.
--
--   Gating mirrors the rest of the family: member-gated overall, and the
--   admin-only settings are omitted entirely (not nulled) for non-admins, so a
--   member cannot infer them from the shape of the response. "Organisation not
--   found" rather than "Permission denied" for a non-member, so the endpoint is
--   not an existence oracle -- the same wording list_organisation_members uses.
--
-- Dependencies:
--   - 20260801000000_organisation_tables.sql
--   - 20260801010000_organisation_permissions_and_guards.sql (resolve_org_actor,
--     user_is_org_member, user_is_org_admin)
--   - 20260803000000_plugins_org_ownership.sql (user_can_publish_org_plugin)
-- ============================================================================


CREATE OR REPLACE FUNCTION "public"."get_organisation_detail"(
    "p_org_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_is_admin BOOLEAN;
    v_org public.organisations%ROWTYPE;
    v_data JSONB;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_member(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    SELECT * INTO v_org FROM public.organisations o WHERE o.id = p_org_id;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    v_is_admin := public.user_is_org_admin(v_actor, p_org_id);

    -- Member-visible projection. owner_id is included because the page renders
    -- "owned by <email>", and ownership is not a secret from the membership.
    v_data := jsonb_build_object(
        'id',           v_org.id::text,
        'slug',         v_org.slug,
        'name',         v_org.name,
        'description',  v_org.description,
        'visibility',   v_org.visibility,
        'join_policy',  v_org.join_policy,
        'is_system',    v_org.is_system,
        'created_at',   v_org.created_at,
        'owner_id',     v_org.owner_id::text,
        'owner_email',  (SELECT u.email FROM auth.users u WHERE u.id = v_org.owner_id),
        'member_count', (SELECT count(*) FROM public.organisation_members m
                          WHERE m.org_id = p_org_id AND m.status = 'active'),
        'pending_count', (SELECT count(*) FROM public.organisation_members m
                           WHERE m.org_id = p_org_id AND m.status = 'pending'),
        'primary_domain', (SELECT d.domain FROM public.organisation_domains d
                            WHERE d.org_id = p_org_id AND d.is_primary AND d.verified LIMIT 1),
        'is_owner',     (v_org.owner_id = v_actor),
        'is_admin',     v_is_admin,
        'can_publish',  public.user_can_publish_org_plugin(v_actor, p_org_id));

    -- Admin-only settings. Merged in rather than emitted as NULLs: a member's
    -- response simply does not carry these keys.
    IF v_is_admin THEN
        v_data := v_data || jsonb_build_object(
            'publish_policy',          v_org.publish_policy,
            'publish_role_id',         v_org.publish_role_id::text,
            'publish_role_name',       (SELECT r.name FROM public.roles r
                                         WHERE r.id = v_org.publish_role_id),
            'auto_assign_member_role', v_org.auto_assign_member_role,
            'max_custom_roles',        v_org.max_custom_roles,
            'custom_role_count',       (SELECT count(*) FROM public.organisation_roles orl
                                         WHERE orl.org_id = p_org_id AND orl.kind = 'custom'),
            'plugin_count',            (SELECT count(*) FROM public.plugins p
                                         WHERE p.org_id = p_org_id));
    END IF;

    RETURN jsonb_build_object('success', true, 'data', v_data);
END;
$$;

ALTER FUNCTION "public"."get_organisation_detail"("uuid", "uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."get_organisation_detail"("uuid", "uuid") FROM PUBLIC, "anon";
GRANT  EXECUTE ON FUNCTION "public"."get_organisation_detail"("uuid", "uuid") TO "authenticated", "service_role";

COMMENT ON FUNCTION "public"."get_organisation_detail"("uuid", "uuid") IS
'Full settings for one organisation, for the admin configuration page. Member-gated; the admin-only settings (publish policy, auto-assign, role caps, plugin count) are absent from a non-admin''s response rather than nulled. p_actor_id is honoured only for service_role -- see resolve_org_actor.';


-- ============================================================================
-- End of File: 20260804000000_organisation_detail_rpc.sql
-- ============================================================================
