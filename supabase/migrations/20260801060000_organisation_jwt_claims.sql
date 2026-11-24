-- ============================================================================
-- BOSS Database Schema: Organisation claims in the access token
-- ============================================================================
-- File: 20260801060000_organisation_jwt_claims.sql
-- Description:
--   Adds `orgs` and `org_admin` (arrays of organisation slugs) to the JWT, so the
--   desktop client can render an organisation switcher and an admin affordance
--   without a round trip on every window.
--
-- ############################################################################
-- ## THESE CLAIMS ARE UI HINTS. THEY ARE NOT AN AUTHORIZATION INPUT.         ##
-- ############################################################################
--
-- Staleness is bounded by jwt_expiry, which is 3600 seconds (supabase/config.toml).
-- A user removed from an organisation keeps `orgs` for up to an hour. Therefore:
--
--   * NO RLS policy may read these claims.
--   * NO RPC may read these claims.
--
-- Every authorization decision goes through is_org_member / is_org_admin /
-- user_is_org_admin, which read the membership tables and are therefore correct
-- the instant a membership changes. The one JWT-claim-based policy that does
-- exist in this schema ("Privileged users can read all users",
-- 20260625000000) is a deliberate RLS-recursion workaround, NOT a precedent to
-- copy here.
--
-- `org_roles` is deliberately NOT added: an organisation's role names already
-- appear in the existing `user_roles` claim, so a third array would be pure
-- duplication on every token.
--
-- PASSKEY CONSISTENCY IS AUTOMATIC. supabase/functions/passkey/utils/jwt.ts mints
-- its session via the admin API (generateLink + verifyOtp), which runs this same
-- hook. No edge-function change is needed for the two sign-in paths to agree.
--
-- Dependencies:
--   - 20260801000000_organisation_tables.sql
--   - 20260625000000_role_hierarchy_and_granular_rbac.sql (the hook this replaces)
--   - 20251023000007_helper_functions.sql                 (get_user_roles_for_hook)
--
-- Next migration: 20260801070000_organisation_seed_boss_org.sql
-- ============================================================================


-- ============================================================================
-- SECTION 1: get_user_orgs_for_hook
-- ============================================================================
-- THREE THINGS HERE ARE LOAD-BEARING. Getting any of them wrong breaks EVERY
-- login, magic link and passkey alike, because the hook runs inside token
-- issuance:
--
--   1. SECURITY DEFINER. The hook executes as supabase_auth_admin. Without
--      DEFINER, this function's reads of organisation_members / organisations
--      would be subject to RLS and would need a policy for that role -- which
--      would then also have to be maintained forever. DEFINER sidesteps it, the
--      same way get_effective_permissions does.
--   2. GRANT EXECUTE TO supabase_auth_admin. Omit it and every login 500s with a
--      permission-denied inside the hook. The pgTAP suite asserts this grant
--      exists AND that authenticated does NOT have it.
--   3. EXCEPTION WHEN OTHERS -> empty claims. Any error here would otherwise
--      propagate out of the hook and fail token issuance. Degrading to empty
--      organisation claims costs the user an organisation switcher; raising costs
--      them the ability to sign in.

CREATE OR REPLACE FUNCTION "public"."get_user_orgs_for_hook"("check_user_id" "uuid")
RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_orgs text[];
    v_admin text[];
BEGIN
    SELECT COALESCE(array_agg(o.slug ORDER BY o.slug), ARRAY[]::text[])
      INTO v_orgs
      FROM public.organisation_members m
      JOIN public.organisations o ON o.id = m.org_id
     WHERE m.user_id = check_user_id
       AND m.status = 'active';

    -- Mirrors user_is_org_admin's role arm. The owner arm is folded in via the
    -- OR, and the global-admin short-circuit is deliberately NOT applied: a
    -- global admin is an admin of every organisation, and listing all of them in
    -- every token would grow without bound. The client already has is_admin.
    SELECT COALESCE(array_agg(DISTINCT o.slug ORDER BY o.slug), ARRAY[]::text[])
      INTO v_admin
      FROM public.organisation_members m
      JOIN public.organisations o ON o.id = m.org_id
     WHERE m.user_id = check_user_id
       AND m.status = 'active'
       AND (
           o.owner_id = check_user_id
           OR EXISTS (
               SELECT 1
               FROM public.organisation_roles orl
               JOIN public.user_roles ur
                 ON ur.role_id = orl.role_id AND ur.user_id = m.user_id
               WHERE orl.org_id = m.org_id AND orl.kind = 'admin'
           )
       );

    RETURN jsonb_build_object('orgs', to_jsonb(v_orgs), 'org_admin', to_jsonb(v_admin));
EXCEPTION WHEN OTHERS THEN
    -- The auth hook must NEVER raise: an exception here fails token issuance for
    -- every login on the instance. Degrade to empty claims instead.
    --
    -- But say so. Silently empty claims are indistinguishable from "this user is in
    -- no organisation", so a missing or revoked supabase_auth_admin grant - the one
    -- the deployment notes call out - would present only as the switcher never
    -- appearing, for everyone, with nothing in any log.
    RAISE WARNING 'get_user_orgs_for_hook failed for %: % (claims degraded to empty)',
        check_user_id, SQLERRM;
    RETURN jsonb_build_object('orgs', '[]'::jsonb, 'org_admin', '[]'::jsonb);
END;
$$;

ALTER FUNCTION "public"."get_user_orgs_for_hook"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_user_orgs_for_hook"("uuid") IS 'Active organisation slugs and admin-of slugs for the JWT hook. SECURITY DEFINER so no RLS policy is needed for supabase_auth_admin on the organisation tables, and exception-wrapped because a raise here would break every login. UI hints only -- never an authorization input.';

REVOKE EXECUTE ON FUNCTION "public"."get_user_orgs_for_hook"("uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_user_orgs_for_hook"("uuid") TO "supabase_auth_admin", "service_role";


-- ============================================================================
-- SECTION 2: custom_access_token_hook
-- ============================================================================
-- Reproduced verbatim from 20260625000000 SECTION 7, with only the organisation
-- claims appended. Everything else -- user_role, user_roles, is_admin,
-- user_permissions -- must keep behaving byte-for-byte identically, because
-- RBACModels.fromJWTClaims on the client reads exactly those four keys.
--
-- The client ignores unknown claim keys, so `orgs` / `org_admin` are inert until
-- a client version reads them. No coordinated release is required.

CREATE OR REPLACE FUNCTION "public"."custom_access_token_hook"("event" "jsonb") RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE
    AS $$
DECLARE
    claims jsonb;
    user_roles_array text[];
    user_perms_array text[];
    primary_role text;
    v_orgs jsonb;
    v_user_id uuid := (event->>'user_id')::uuid;
BEGIN
    claims := event->'claims';

    user_roles_array := public.get_user_roles_for_hook(v_user_id);
    user_perms_array := public.get_effective_permissions(v_user_id);

    IF user_roles_array IS NOT NULL AND array_length(user_roles_array, 1) > 0 THEN
        primary_role := user_roles_array[1];
    ELSE
        primary_role := 'user';
    END IF;

    IF user_roles_array IS NOT NULL THEN
        claims := jsonb_set(claims, '{user_role}', to_jsonb(primary_role));
        claims := jsonb_set(claims, '{user_roles}', to_jsonb(user_roles_array));
        IF 'admin' = ANY(user_roles_array) THEN
            claims := jsonb_set(claims, '{is_admin}', to_jsonb(true));
        ELSE
            claims := jsonb_set(claims, '{is_admin}', to_jsonb(false));
        END IF;
    ELSE
        claims := jsonb_set(claims, '{user_role}', to_jsonb('user'::text));
        claims := jsonb_set(claims, '{user_roles}', to_jsonb(ARRAY['user']::text[]));
        claims := jsonb_set(claims, '{is_admin}', to_jsonb(false));
    END IF;

    -- Effective permissions (own + inherited via the role hierarchy)
    claims := jsonb_set(claims, '{user_permissions}', to_jsonb(COALESCE(user_perms_array, ARRAY[]::text[])));

    -- Organisation membership (20260801060000). UI HINTS ONLY -- stale for up to
    -- jwt_expiry (3600s), so no policy and no RPC may authorize on them.
    -- get_user_orgs_for_hook never raises; the COALESCEs are a second belt in case
    -- the function is ever missing entirely.
    v_orgs := public.get_user_orgs_for_hook(v_user_id);
    claims := jsonb_set(claims, '{orgs}',      COALESCE(v_orgs -> 'orgs',      '[]'::jsonb));
    claims := jsonb_set(claims, '{org_admin}', COALESCE(v_orgs -> 'org_admin', '[]'::jsonb));

    event := jsonb_set(event, '{claims}', claims);
    RETURN event;
END;
$$;

ALTER FUNCTION "public"."custom_access_token_hook"("event" "jsonb") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") IS 'Supabase auth hook. Injects user_role, user_roles, is_admin, user_permissions and -- as of 20260801060000 -- orgs and org_admin. The organisation claims are UI hints with up to jwt_expiry staleness; authorization always reads the tables via is_org_member / is_org_admin.';

-- Re-issued verbatim from 20251023000014_grants.sql. CREATE OR REPLACE preserves
-- grants, but this hook is the single most breakage-sensitive object in the
-- schema, so they are restated rather than assumed.
REVOKE ALL   ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") FROM PUBLIC;
GRANT  ALL   ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") TO "anon";
GRANT  ALL   ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") TO "authenticated";
GRANT  ALL   ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") TO "service_role";
GRANT  ALL   ON FUNCTION "public"."custom_access_token_hook"("event" "jsonb") TO "supabase_auth_admin";


-- ============================================================================
-- End of File: 20260801060000_organisation_jwt_claims.sql
-- ============================================================================
-- Next Migration: 20260801070000_organisation_seed_boss_org.sql
-- ============================================================================
