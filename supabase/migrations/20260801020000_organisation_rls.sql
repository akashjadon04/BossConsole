-- ============================================================================
-- BOSS Database Schema: Organisation RLS policies
-- ============================================================================
-- File: 20260801020000_organisation_rls.sql
-- Description:
--   Row-level security for the nine organisation tables, plus a cross-org
--   tightening of the two existing role-delegation RPCs.
--
-- Dependencies:
--   - 20260801000000_organisation_tables.sql              (the tables, RLS enabled)
--   - 20260801010000_organisation_permissions_and_guards.sql (the predicates)
--   - 20260625000000_role_hierarchy_and_granular_rbac.sql  (authorize,
--                                                           assign/remove_permission_from_role)
--
-- Next migration: 20260801030000_organisation_core_rpcs.sql
-- ============================================================================


-- ============================================================================
-- Policy design -- read this before adding a policy here
-- ============================================================================
--
-- THESE TABLES HAVE SELECT POLICIES ONLY. The absence of INSERT / UPDATE /
-- DELETE policies for `authenticated` is the design, not an oversight:
-- every mutation goes through a SECURITY DEFINER RPC in migrations 4-7, which
-- runs as the function owner and therefore bypasses RLS. Concentrating writes
-- there is what lets a single function enforce a multi-step invariant (create an
-- org AND its roles AND its hierarchy edges AND the founder membership, or
-- none of it). A permissive INSERT policy would open a second, unguarded path to
-- the same tables.
--
-- Every membership predicate below is SECURITY DEFINER, which is what avoids
-- "infinite recursion detected in policy for relation organisation_members" --
-- the same trick is_user_admin() plays for user_roles.
--
-- authorize() appears exactly twice in this file, both times for
-- 'organisation.approve'. That is correct: reviewing organisation-creation
-- requests is a genuinely GLOBAL permission with no org scope. Everywhere else
-- the gate is is_org_member/is_org_admin. See the H1 note in
-- 20260801000000_organisation_tables.sql.
--
-- The service-role policies follow the existing repo shape,
--   USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"))
-- as in 20251023000013_rls_policies.sql.


-- ============================================================================
-- SECTION 1: organisations
-- ============================================================================

DROP POLICY IF EXISTS "Public and member organisations are viewable" ON "public"."organisations";
CREATE POLICY "Public and member organisations are viewable" ON "public"."organisations"
    FOR SELECT TO "authenticated"
    USING ("visibility" = 'public' OR "public"."is_org_member"("id"));

-- Reviewers must see every organisation, including private ones, to judge a
-- request for a similar name and to audit what exists.
DROP POLICY IF EXISTS "Organisation reviewers can view all organisations" ON "public"."organisations";
CREATE POLICY "Organisation reviewers can view all organisations" ON "public"."organisations"
    FOR SELECT TO "authenticated"
    USING ("public"."authorize"('organisation.approve'));

DROP POLICY IF EXISTS "Service role full access to organisations" ON "public"."organisations";
CREATE POLICY "Service role full access to organisations" ON "public"."organisations"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- ============================================================================
-- SECTION 2: organisation_members
-- ============================================================================
-- Three SELECT arms, deliberately separate rather than one OR: a user must
-- always see their OWN row (including a pending application to an org they are
-- not yet a member of, which the is_org_member arm would hide).

DROP POLICY IF EXISTS "Users can view their own memberships" ON "public"."organisation_members";
CREATE POLICY "Users can view their own memberships" ON "public"."organisation_members"
    FOR SELECT TO "authenticated"
    USING ("user_id" = "auth"."uid"());

DROP POLICY IF EXISTS "Organisation members can view the roster" ON "public"."organisation_members";
-- The roster, EXCEPT for a system organisation.
--
-- Every user is an active member of the seeded boss org, so a bare
-- is_org_member() test would make organisation_members a directory of every
-- account in the deployment - readable directly over PostgREST, not only through
-- the RPC. The RPC has the matching restriction; both are needed, because RLS is
-- the one an ordinary client can query without going through us.
CREATE POLICY "Organisation members can view the roster" ON "public"."organisation_members"
    FOR SELECT TO "authenticated"
    USING (
        "status" = 'active'
        AND "public"."is_org_member"("org_id")
        AND (
            "user_id" = "auth"."uid"()
            OR "public"."is_org_admin"("org_id")
            OR NOT EXISTS (
                SELECT 1 FROM "public"."organisations" o
                WHERE o."id" = "organisation_members"."org_id" AND o."is_system"
            )
        )
    );

DROP POLICY IF EXISTS "Organisation admins can view pending and invited members" ON "public"."organisation_members";
CREATE POLICY "Organisation admins can view pending and invited members" ON "public"."organisation_members"
    FOR SELECT TO "authenticated"
    USING ("public"."is_org_admin"("org_id"));

DROP POLICY IF EXISTS "Service role full access to organisation members" ON "public"."organisation_members";
CREATE POLICY "Service role full access to organisation members" ON "public"."organisation_members"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- ============================================================================
-- SECTION 3: organisation_roles
-- ============================================================================

DROP POLICY IF EXISTS "Organisation members can view their organisation roles" ON "public"."organisation_roles";
CREATE POLICY "Organisation members can view their organisation roles" ON "public"."organisation_roles"
    FOR SELECT TO "authenticated"
    USING ("public"."is_org_member"("org_id"));

DROP POLICY IF EXISTS "Service role full access to organisation roles" ON "public"."organisation_roles";
CREATE POLICY "Service role full access to organisation roles" ON "public"."organisation_roles"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- ============================================================================
-- SECTION 4: organisation_domains
-- ============================================================================
-- Verified domains are discovery metadata -- search_organisations has to match a
-- caller's email domain against them, and the discovery UI shows an org's
-- primary domain. Unverified rows stay private to the organisation.
--
-- verification_token lives on these rows. It is low-value on its own (it only
-- proves DNS control of a domain the org is claiming, and is useless without
-- also controlling that DNS), but it is only ever projected by the org-admin
-- RPC -- the desktop client never selects this table directly.

DROP POLICY IF EXISTS "Organisation members can view organisation domains" ON "public"."organisation_domains";
CREATE POLICY "Organisation members can view organisation domains" ON "public"."organisation_domains"
    FOR SELECT TO "authenticated"
    USING ("public"."is_org_member"("org_id") OR "verified" = true);

DROP POLICY IF EXISTS "Service role full access to organisation domains" ON "public"."organisation_domains";
CREATE POLICY "Service role full access to organisation domains" ON "public"."organisation_domains"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- ============================================================================
-- SECTION 5: reserved_email_domains
-- ============================================================================
-- World-readable to authenticated users so the desktop UI can tell someone
-- "gmail.com cannot be registered" before they submit the form, instead of
-- surfacing a server error afterwards.

DROP POLICY IF EXISTS "Anyone can view reserved email domains" ON "public"."reserved_email_domains";
CREATE POLICY "Anyone can view reserved email domains" ON "public"."reserved_email_domains"
    FOR SELECT TO "authenticated"
    USING (true);

DROP POLICY IF EXISTS "Service role full access to reserved email domains" ON "public"."reserved_email_domains";
CREATE POLICY "Service role full access to reserved email domains" ON "public"."reserved_email_domains"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- ============================================================================
-- SECTION 6: organisation_requests
-- ============================================================================

DROP POLICY IF EXISTS "Requesters can view their own organisation requests" ON "public"."organisation_requests";
CREATE POLICY "Requesters can view their own organisation requests" ON "public"."organisation_requests"
    FOR SELECT TO "authenticated"
    USING ("requester_id" = "auth"."uid"());

DROP POLICY IF EXISTS "Reviewers can view all organisation requests" ON "public"."organisation_requests";
CREATE POLICY "Reviewers can view all organisation requests" ON "public"."organisation_requests"
    FOR SELECT TO "authenticated"
    USING ("public"."authorize"('organisation.approve'));

DROP POLICY IF EXISTS "Service role full access to organisation requests" ON "public"."organisation_requests";
CREATE POLICY "Service role full access to organisation requests" ON "public"."organisation_requests"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- ============================================================================
-- SECTION 7: organisation_invites and redemptions
-- ============================================================================
-- organisation_invites carries token_hash and is NOT granted to authenticated in
-- 20260801000000, so this SELECT policy only takes effect for a role that has
-- the table grant. It is written anyway as defence in depth: if a future
-- migration adds a grant, the policy is already correct. The client reads
-- invites through list_organisation_invites, which projects token_prefix and
-- never token_hash.

DROP POLICY IF EXISTS "Organisation admins can view organisation invites" ON "public"."organisation_invites";
CREATE POLICY "Organisation admins can view organisation invites" ON "public"."organisation_invites"
    FOR SELECT TO "authenticated"
    USING ("public"."is_org_admin"("org_id"));

DROP POLICY IF EXISTS "Service role full access to organisation invites" ON "public"."organisation_invites";
CREATE POLICY "Service role full access to organisation invites" ON "public"."organisation_invites"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


DROP POLICY IF EXISTS "Users can view their own invite redemptions" ON "public"."organisation_invite_redemptions";
CREATE POLICY "Users can view their own invite redemptions" ON "public"."organisation_invite_redemptions"
    FOR SELECT TO "authenticated"
    USING ("user_id" = "auth"."uid"());

DROP POLICY IF EXISTS "Organisation admins can view invite redemptions" ON "public"."organisation_invite_redemptions";
CREATE POLICY "Organisation admins can view invite redemptions" ON "public"."organisation_invite_redemptions"
    FOR SELECT TO "authenticated"
    USING (EXISTS (
        SELECT 1 FROM "public"."organisation_invites" i
        WHERE i."id" = "organisation_invite_redemptions"."invite_id"
          AND "public"."is_org_admin"(i."org_id")
    ));

DROP POLICY IF EXISTS "Service role full access to invite redemptions" ON "public"."organisation_invite_redemptions";
CREATE POLICY "Service role full access to invite redemptions" ON "public"."organisation_invite_redemptions"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- ============================================================================
-- SECTION 8: organisation_handoff_tokens
-- ============================================================================
-- NO policy for authenticated, deliberately. RLS is enabled with zero
-- permissive policies for that role, which is deny-all: only the SECURITY
-- DEFINER mint/consume RPCs and service_role can touch this table. The hash
-- column must never be readable -- a client that could read token_hash could
-- confirm a guessed token offline.

DROP POLICY IF EXISTS "Service role full access to handoff tokens" ON "public"."organisation_handoff_tokens";
CREATE POLICY "Service role full access to handoff tokens" ON "public"."organisation_handoff_tokens"
    USING ((("auth"."jwt"() ->> 'role'::"text") = 'service_role'::"text"));


-- ============================================================================
-- SECTION 9: Cross-organisation tightening of the delegation guard
-- ============================================================================
-- Additive to 20260625000000 SECTIONS 5.6/5.7. Both bodies are reproduced
-- verbatim with ONE new check inserted inside the existing
-- `IF NOT public.is_user_admin(v_user_id) THEN` block.
--
-- What it closes: a non-admin holding role.update whose delegated scope happens
-- to include an organisation's role -- because the org's roles sit below theirs
-- in the hierarchy -- could otherwise modify another organisation's permissions.
-- Today the tree does not produce that overlap, but it is one hierarchy edit
-- away from doing so, and the failure would be silent.
--
-- Placed INSIDE the non-admin block, so global admins keep their existing
-- bypass. That is consistent with every other guard in 20260625000000, and it
-- is safe because Guard 2 (enforce_org_role_permission_scope) already stops an
-- admin attaching anything dangerous to an organisation role regardless.

CREATE OR REPLACE FUNCTION "public"."assign_permission_to_role"("role_name" "text", "permission_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
    v_permission_id UUID;
    v_role_org_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.update') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT id INTO v_role_id FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    SELECT id INTO v_permission_id FROM public.permissions WHERE name = permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Permission "%s" not found', permission_name));
    END IF;

    -- Delegation guard for non-admin role.update holders (see DELEGATION GUARD note).
    IF NOT public.is_user_admin(v_user_id) THEN
        IF v_role_id NOT IN (SELECT public.get_grantable_role_ids(v_user_id)) THEN
            RETURN jsonb_build_object('success', false, 'error', format('Permission denied: role "%s" is not in your delegated scope', role_name));
        END IF;
        IF NOT public.authorize(permission_name) THEN
            RETURN jsonb_build_object('success', false, 'error', format('Permission denied: cannot grant a permission you do not hold ("%s")', permission_name));
        END IF;

        -- Cross-organisation guard (20260801020000). A delegated role.update
        -- holder may not reach into an organisation they do not administer, even
        -- if the hierarchy makes that org's role a descendant of one of theirs.
        SELECT orl.org_id INTO v_role_org_id
        FROM public.organisation_roles orl WHERE orl.role_id = v_role_id;
        IF v_role_org_id IS NOT NULL AND NOT public.user_is_org_admin(v_user_id, v_role_org_id) THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('Permission denied: role "%s" belongs to an organisation you do not administer', role_name));
        END IF;
    END IF;

    INSERT INTO public.role_permissions (role_id, permission_id)
    VALUES (v_role_id, v_permission_id)
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    RETURN jsonb_build_object('success', true, 'message', format('Permission "%s" assigned to role "%s"', permission_name, role_name));
END;
$$;


CREATE OR REPLACE FUNCTION "public"."remove_permission_from_role"("role_name" "text", "permission_name" "text") RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_role_id UUID;
    v_permission_id UUID;
    v_role_org_id UUID;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.authorize('role.update') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT id INTO v_role_id FROM public.roles WHERE name = role_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Role "%s" not found', role_name));
    END IF;

    SELECT id INTO v_permission_id FROM public.permissions WHERE name = permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', format('Permission "%s" not found', permission_name));
    END IF;

    -- Delegation guard for non-admin role.update holders (see DELEGATION GUARD note).
    IF NOT public.is_user_admin(v_user_id) THEN
        IF v_role_id NOT IN (SELECT public.get_grantable_role_ids(v_user_id)) THEN
            RETURN jsonb_build_object('success', false, 'error', format('Permission denied: role "%s" is not in your delegated scope', role_name));
        END IF;
        IF NOT public.authorize(permission_name) THEN
            RETURN jsonb_build_object('success', false, 'error', format('Permission denied: cannot modify a permission you do not hold ("%s")', permission_name));
        END IF;

        -- Cross-organisation guard (20260801020000). See assign_permission_to_role.
        SELECT orl.org_id INTO v_role_org_id
        FROM public.organisation_roles orl WHERE orl.role_id = v_role_id;
        IF v_role_org_id IS NOT NULL AND NOT public.user_is_org_admin(v_user_id, v_role_org_id) THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('Permission denied: role "%s" belongs to an organisation you do not administer', role_name));
        END IF;
    END IF;

    DELETE FROM public.role_permissions
    WHERE role_id = v_role_id AND permission_id = v_permission_id;

    RETURN jsonb_build_object('success', true, 'message', format('Permission "%s" removed from role "%s"', permission_name, role_name));
END;
$$;

-- Grants are unchanged by CREATE OR REPLACE (same signature), but re-issued to
-- keep this migration self-describing and re-runnable in isolation.
REVOKE EXECUTE ON FUNCTION "public"."assign_permission_to_role"("text", "text")   FROM PUBLIC, "anon";
REVOKE EXECUTE ON FUNCTION "public"."remove_permission_from_role"("text", "text") FROM PUBLIC, "anon";
GRANT  EXECUTE ON FUNCTION "public"."assign_permission_to_role"("text", "text")   TO "authenticated", "service_role";
GRANT  EXECUTE ON FUNCTION "public"."remove_permission_from_role"("text", "text") TO "authenticated", "service_role";


-- ============================================================================
-- End of File: 20260801020000_organisation_rls.sql
-- ============================================================================
-- Next Migration: 20260801030000_organisation_core_rpcs.sql
-- ============================================================================
