-- ============================================================================
-- BOSS Database Schema: Organisation core RPCs
-- ============================================================================
-- File: 20260801030000_organisation_core_rpcs.sql
-- Description:
--   Creation, requests, membership, organisation roles, domains, settings and
--   discovery. Every mutation to the organisation tables happens here or in the
--   two migrations that follow -- there are no INSERT/UPDATE/DELETE RLS policies
--   for authenticated, by design (see 20260801020000).
--
-- Conventions (all functions in this file):
--   LANGUAGE plpgsql SECURITY DEFINER SET search_path TO ''
--   fully-qualified public. / auth. / extensions. names
--   RETURNS jsonb shaped { success: true, ... } or { success: false, error: text }
--   -- the shape 20251023000004_secret_functions.sql established.
--
-- ONE PLPGSQL FUNCTION IS ONE TRANSACTION. That is what makes
-- create_organisation_internal atomic across nine inserts, and what makes
-- approve_organisation_request either produce a complete organisation or leave
-- the request untouched.
--
-- THE p_actor_id PARAMETER, on every gated function
--   Each function below resolves who is acting via
--   public.resolve_org_actor(p_actor_id) instead of reading auth.uid() directly,
--   and then gates on the explicit-subject predicates
--   user_is_org_admin(v_actor, org) / user_is_org_member(v_actor, org) /
--   user_holds_permission(v_actor, perm).
--
--   For a signed-in caller p_actor_id is IGNORED -- auth.uid() wins
--   unconditionally, so an authenticated user cannot impersonate anyone by
--   passing it. It is honoured only for a service_role caller, which in practice
--   means the `organisation` edge function serving the org and
--   admin-configuration web pages: it holds a handoff-derived user_id, not a
--   JWT, so auth.uid() there is NULL.
--
--   The alternative was a parallel *_for_actor family -- ~17 near-duplicate
--   functions, two copies of every gate to keep in step. See the long note on
--   resolve_org_actor in 20260801010000 for the full comparison.
--
-- ERROR SHAPE: an unauthorized caller gets "Organisation not found" from read
-- paths rather than "Permission denied", so those RPCs are not membership
-- oracles. Write paths return "Permission denied", where the caller already knows
-- the organisation exists.
--
-- Dependencies:
--   - 20260801000000_organisation_tables.sql
--   - 20260801010000_organisation_permissions_and_guards.sql
--   - 20260801020000_organisation_rls.sql
--
-- Next migration: 20260801040000_organisation_invites.sql
-- ============================================================================


-- ============================================================================
-- SECTION 1: organisation_available_action -- shared eligibility logic
-- ============================================================================
-- Discovery and join must never disagree about what a user may do with an
-- organisation. Both call this. Duplicating the CASE in search_organisations and
-- join_organisation is how you end up with a UI offering a Join button that the
-- server then refuses.
--
-- Returns one of:
--   'member'  -- already an active member
--   'pending' -- has a pending or invited row
--   'join'    -- may join immediately (open policy, or a verified domain match)
--   'request' -- may apply, an organisation admin decides
--   'none'    -- invite_only, or no relationship at all
--
-- The domain arm requires ALL THREE of: the organisation's domain is verified,
-- the user's email is CONFIRMED, and that domain is not a reserved consumer
-- mailbox. Drop any one and "verify a domain" becomes "absorb every signup on
-- it".
--
-- A verified domain match UPGRADES request_to_join to an immediate join -- it
-- does NOT override invite_only. An organisation that chose invite_only has
-- stated that no self-service entry exists, and owning a domain does not undo
-- that choice. This is a deliberate narrowing of "auto-join by domain" to the one
-- policy where self-service was already on the table.
CREATE OR REPLACE FUNCTION "public"."organisation_available_action"("p_user_id" "uuid", "p_org_id" "uuid")
RETURNS "text"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_status TEXT;
    v_join_policy TEXT;
    v_domain_match BOOLEAN;
BEGIN
    IF p_user_id IS NULL OR p_org_id IS NULL THEN
        RETURN 'none';
    END IF;

    SELECT m.status INTO v_status
    FROM public.organisation_members m
    WHERE m.org_id = p_org_id AND m.user_id = p_user_id;

    IF v_status = 'active' THEN
        RETURN 'member';
    ELSIF v_status IN ('pending', 'invited') THEN
        RETURN 'pending';
    END IF;

    SELECT o.join_policy INTO v_join_policy
    FROM public.organisations o WHERE o.id = p_org_id;
    IF NOT FOUND THEN
        RETURN 'none';
    END IF;

    IF v_join_policy = 'open' THEN
        RETURN 'join';
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM auth.users u
        JOIN public.organisation_domains d
          ON d.org_id = p_org_id
         AND d.verified = true
         AND d.domain = lower(split_part(u.email, '@', 2))
        WHERE u.id = p_user_id
          AND u.email IS NOT NULL
          AND u.email_confirmed_at IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM public.reserved_email_domains red
              WHERE red.domain = lower(split_part(u.email, '@', 2))
          )
    ) INTO v_domain_match;

    IF v_join_policy = 'request_to_join' THEN
        -- A verified-domain member of a request_to_join organisation skips the queue.
        RETURN CASE WHEN v_domain_match THEN 'join' ELSE 'request' END;
    ELSE
        -- invite_only: no self-service entry at all, domain match or not.
        RETURN 'none';
    END IF;
END;
$$;

ALTER FUNCTION "public"."organisation_available_action"("uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."organisation_available_action"("uuid", "uuid") IS 'What a user may do with an organisation: member | pending | join | request | none. Shared by search_organisations, join_organisation and request_organisation_membership so the UI and the server can never disagree.';

REVOKE EXECUTE ON FUNCTION "public"."organisation_available_action"("uuid", "uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."organisation_available_action"("uuid", "uuid") TO "service_role";


-- ============================================================================
-- SECTION 2: create_organisation_internal -- THE single write path
-- ============================================================================
-- service_role ONLY. It takes an arbitrary p_owner_id, so exposing it to
-- authenticated would let any user create an organisation owned by -- and
-- administered by -- someone else. Its only callers are
-- approve_organisation_request (SECURITY DEFINER, so it runs as the owner and
-- may call this) and the boss-org seed.
--
-- Everything below happens in one transaction. A failure at step 7 leaves no
-- organisation, no orphan roles and no dangling hierarchy edges.

CREATE OR REPLACE FUNCTION "public"."create_organisation_internal"(
    "p_slug" "text",
    "p_name" "text",
    "p_description" "text" DEFAULT NULL::"text",
    "p_owner_id" "uuid" DEFAULT NULL::"uuid",
    "p_domain" "text" DEFAULT NULL::"text",
    "p_visibility" "text" DEFAULT 'private'::"text",
    "p_join_policy" "text" DEFAULT 'invite_only'::"text",
    "p_is_system" boolean DEFAULT false,
    "p_admin_role_name" "text" DEFAULT NULL::"text",
    "p_user_role_name" "text" DEFAULT NULL::"text",
    "p_auto_assign_member_role" boolean DEFAULT true
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_org_id UUID;
    v_admin_name TEXT;
    v_user_name TEXT;
    v_admin_role_id UUID;
    v_user_role_id UUID;
    v_base_role_id UUID;
    v_domain TEXT;
    v_token TEXT;
BEGIN
    IF p_owner_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'An owner is required');
    END IF;

    IF p_slug IS NULL OR NOT (p_slug ~ '^[a-z][a-z0-9_]{1,30}$') THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Slug must be 2-31 characters, lowercase letters, digits and underscores, starting with a letter');
    END IF;

    IF p_visibility NOT IN ('private', 'public') THEN
        RETURN jsonb_build_object('success', false, 'error', 'visibility must be private or public');
    END IF;

    IF p_join_policy NOT IN ('invite_only', 'request_to_join', 'open') THEN
        RETURN jsonb_build_object('success', false, 'error',
            'join_policy must be invite_only, request_to_join or open');
    END IF;

    -- p_is_system bypasses the reserved-slug check. Only the boss-org seed passes
    -- it, and only because 'boss' is on the reserved list precisely BECAUSE of the
    -- boss_admin collision -- which the seed sidesteps by passing explicit
    -- boss_org_* role names.
    IF NOT p_is_system AND public.is_reserved_organisation_slug(p_slug) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Slug "%s" is reserved, or its role names (%s, %s) are already taken',
                   p_slug,
                   public.organisation_role_name(p_slug, 'admin'),
                   public.organisation_role_name(p_slug, 'user')));
    END IF;

    v_admin_name := COALESCE(p_admin_role_name, public.organisation_role_name(p_slug, 'admin'));
    v_user_name  := COALESCE(p_user_role_name,  public.organisation_role_name(p_slug, 'user'));

    -- Re-check the derived names even for a system organisation. This is the H2
    -- guard on the write path: mapping an EXISTING role (which could be a global
    -- system role such as boss_admin) into organisation_roles is the escalation.
    IF EXISTS (SELECT 1 FROM public.roles r WHERE r.name IN (v_admin_name, v_user_name)) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Role name "%s" or "%s" already exists', v_admin_name, v_user_name));
    END IF;

    -- public.roles.name is validated ^[a-z][a-z0-9_]{2,50}$ by create_new_role;
    -- enforce the length here too so a 31-character slug cannot produce a
    -- 37-character name that a later role RPC would reject.
    IF char_length(v_admin_name) > 50 OR char_length(v_user_name) > 50 THEN
        RETURN jsonb_build_object('success', false, 'error', 'Slug is too long for the derived role names');
    END IF;

    -- The optional domain claim is validated HERE, before the first INSERT, and
    -- the placement is the whole point.
    --
    -- A plain RETURN from PL/pgSQL is a normal return: it rolls back NOTHING.
    -- Only a propagating exception aborts the transaction. These two checks used
    -- to sit at step 8, after the organisation, both roles, the hierarchy edges,
    -- the permission grants, the founder membership and the founder user_roles
    -- row had all been inserted -- so a refused domain returned success:false
    -- while all eight inserts committed. That left an orphan organisation nothing
    -- pointed at, silently owned by the requester, and because the slug was now
    -- taken the originating request could never be approved on a retry.
    --
    -- Reachable in normal operation: two pending requests naming the same domain,
    -- or a domain added to reserved_email_domains between submit and approve.
    -- submit_organisation_request checks the domain too, but that check is a
    -- TOCTOU, which is exactly why the re-check exists.
    IF p_domain IS NOT NULL AND btrim(p_domain) <> '' THEN
        v_domain := lower(btrim(p_domain));

        IF EXISTS (SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = v_domain) THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('"%s" is a reserved email domain and cannot be claimed by an organisation', v_domain));
        END IF;

        IF EXISTS (SELECT 1 FROM public.organisation_domains d WHERE d.domain = v_domain) THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('Domain "%s" is already claimed by another organisation', v_domain));
        END IF;
    END IF;

    -- 1. The organisation.
    BEGIN
        INSERT INTO public.organisations (
            slug, name, description, visibility, join_policy,
            owner_id, created_by, is_system, auto_assign_member_role
        ) VALUES (
            p_slug, btrim(p_name), p_description, p_visibility, p_join_policy,
            p_owner_id, p_owner_id, p_is_system, p_auto_assign_member_role
        ) RETURNING id INTO v_org_id;
    EXCEPTION WHEN unique_violation THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('An organisation with slug "%s" already exists', p_slug));
    END;

    -- 2. The two roles. is_system = false is REQUIRED: enforce_org_role_not_system
    --    refuses to map a system role, and delete_role refuses to delete one, so a
    --    system-flagged organisation role would be both unmappable and uncleanable.
    INSERT INTO public.roles (name, description, is_system)
    VALUES (v_admin_name, format('Administrators of the "%s" organisation', btrim(p_name)), false)
    RETURNING id INTO v_admin_role_id;

    INSERT INTO public.roles (name, description, is_system)
    VALUES (v_user_name, format('Members of the "%s" organisation', btrim(p_name)), false)
    RETURNING id INTO v_user_role_id;

    -- 3. The mapping that makes them organisation roles.
    INSERT INTO public.organisation_roles (org_id, role_id, kind, created_by) VALUES
        (v_org_id, v_admin_role_id, 'admin', p_owner_id),
        (v_org_id, v_user_role_id,  'user',  p_owner_id);

    -- 4. Hierarchy: <slug>_admin -> <slug>_user -> user.
    --    The second edge is what H3 is about: it puts the global `user` role in an
    --    organisation admin's get_grantable_role_ids. That is rendered inert by
    --    enforce_org_role_permission_scope, which makes role.* / user.* /
    --    api_key.* / plugins.admin.* un-grantable to any organisation role.
    SELECT r.id INTO v_base_role_id FROM public.roles r WHERE r.name = 'user';

    INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
    VALUES (v_admin_role_id, v_user_role_id)
    ON CONFLICT DO NOTHING;

    IF v_base_role_id IS NOT NULL THEN
        INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
        VALUES (v_user_role_id, v_base_role_id)
        ON CONFLICT DO NOTHING;
    END IF;

    -- 5. Permissions. All of these pass is_org_grantable_permission's allowlist.
    INSERT INTO public.role_permissions (role_id, permission_id)
    SELECT v_admin_role_id, p.id FROM public.permissions p
    WHERE p.name IN ('organisation.admin', 'organisation.read')
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    INSERT INTO public.role_permissions (role_id, permission_id)
    SELECT v_user_role_id, p.id FROM public.permissions p
    WHERE p.name = 'organisation.read'
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    -- 6. Founder membership.
    INSERT INTO public.organisation_members (org_id, user_id, status, joined_at, join_source)
    VALUES (v_org_id, p_owner_id, 'active', now(), 'founder')
    ON CONFLICT (org_id, user_id) DO NOTHING;

    -- 7. The founder holds the admin role. assigned_by is NULL: a system
    --    assignment, matching handle_new_user's convention for the base role.
    INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
    VALUES (p_owner_id, v_admin_role_id, NULL, now())
    ON CONFLICT (user_id, role_id) DO NOTHING;

    -- 8. Optional unverified domain claim. Already validated above, before the
    --    first INSERT, so nothing here can return a failure after committing rows.
    --    The unique index on organisation_domains.domain is the backstop for a
    --    concurrent claim landing between that check and this insert; it RAISES,
    --    which does roll the transaction back.
    IF p_domain IS NOT NULL AND btrim(p_domain) <> '' THEN
        -- URL-safe: translate()'s 3-character FROM against a 2-character TO also
        -- DELETES the '=' padding.
        v_token := translate(
            pg_catalog.encode(extensions.gen_random_bytes(24), 'base64'), '+/=', '-_');

        INSERT INTO public.organisation_domains (
            org_id, domain, is_primary, verification_token, created_by
        ) VALUES (v_org_id, v_domain, true, v_token, p_owner_id);
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'org_id', v_org_id::text,
        'slug', p_slug,
        'admin_role', v_admin_name,
        'user_role', v_user_name);
END;
$$;

ALTER FUNCTION "public"."create_organisation_internal"("text", "text", "text", "uuid", "text", "text", "text", boolean, "text", "text", boolean) OWNER TO "postgres";

COMMENT ON FUNCTION "public"."create_organisation_internal"("text", "text", "text", "uuid", "text", "text", "text", boolean, "text", "text", boolean) IS 'Creates an organisation with its admin/user roles, hierarchy edges, permission grants, founder membership and optional domain claim -- atomically. service_role ONLY: it accepts an arbitrary owner, so it must never be reachable from PostgREST as authenticated. Callers: approve_organisation_request and the boss-org seed.';

REVOKE EXECUTE ON FUNCTION "public"."create_organisation_internal"("text", "text", "text", "uuid", "text", "text", "text", boolean, "text", "text", boolean) FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."create_organisation_internal"("text", "text", "text", "uuid", "text", "text", "text", boolean, "text", "text", boolean) TO "service_role";


-- ============================================================================
-- SECTION 3: Organisation-creation requests
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."submit_organisation_request"(
    "p_name" "text",
    "p_slug" "text",
    "p_description" "text" DEFAULT NULL::"text",
    "p_domain" "text" DEFAULT NULL::"text",
    "p_justification" "text" DEFAULT NULL::"text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_request_id UUID;
    v_pending_count INTEGER;
    v_domain TEXT;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- A genuinely global permission, so a permission check is the right gate.
    IF NOT public.user_holds_permission(v_actor, 'organisation.create') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    IF p_name IS NULL OR char_length(btrim(p_name)) < 2 OR char_length(btrim(p_name)) > 100 THEN
        RETURN jsonb_build_object('success', false, 'error', 'Name must be 2-100 characters');
    END IF;

    IF p_slug IS NULL OR NOT (p_slug ~ '^[a-z][a-z0-9_]{1,30}$') THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Slug must be 2-31 characters, lowercase letters, digits and underscores, starting with a letter');
    END IF;

    IF public.is_reserved_organisation_slug(p_slug) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Slug "%s" is reserved or already in use', p_slug));
    END IF;

    IF EXISTS (SELECT 1 FROM public.organisations o WHERE o.slug = p_slug) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('An organisation with slug "%s" already exists', p_slug));
    END IF;

    -- Three live requests per person is enough for legitimate use and keeps the
    -- reviewer queue from being floodable.
    SELECT count(*) INTO v_pending_count
    FROM public.organisation_requests r
    WHERE r.requester_id = v_actor AND r.status = 'pending';
    IF v_pending_count >= 3 THEN
        RETURN jsonb_build_object('success', false, 'error',
            'You already have 3 pending organisation requests. Withdraw one before submitting another.');
    END IF;

    IF p_domain IS NOT NULL AND btrim(p_domain) <> '' THEN
        v_domain := lower(btrim(p_domain));
        IF NOT (v_domain ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$') THEN
            RETURN jsonb_build_object('success', false, 'error', 'That is not a valid domain name');
        END IF;
        IF EXISTS (SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = v_domain) THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('"%s" is a reserved email domain and cannot be claimed by an organisation', v_domain));
        END IF;
        IF EXISTS (SELECT 1 FROM public.organisation_domains d WHERE d.domain = v_domain) THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('Domain "%s" is already claimed by another organisation', v_domain));
        END IF;
    END IF;

    BEGIN
        INSERT INTO public.organisation_requests (
            requester_id, name, slug, description, domain, justification, status
        ) VALUES (
            v_actor, btrim(p_name), p_slug, p_description, v_domain, p_justification, 'pending'
        ) RETURNING id INTO v_request_id;
    EXCEPTION WHEN unique_violation THEN
        -- idx_organisation_requests_pending_slug
        RETURN jsonb_build_object('success', false, 'error',
            format('A pending request for slug "%s" already exists', p_slug));
    END;

    RETURN jsonb_build_object('success', true, 'request_id', v_request_id::text, 'status', 'pending');
END;
$$;

ALTER FUNCTION "public"."submit_organisation_request"("text", "text", "text", "text", "text", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."submit_organisation_request"("text", "text", "text", "text", "text", "uuid") IS 'Submits an organisation-creation request. Gated on the global organisation.create permission; revoking that from the "user" role disables self-service organisation creation platform-wide. Also called by the Toolbox Create Organisation dialog.';


CREATE OR REPLACE FUNCTION "public"."approve_organisation_request"(
    "p_request_id" "uuid",
    "p_notes" "text" DEFAULT NULL::"text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_req public.organisation_requests;
    v_result JSONB;
    v_org_id UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_holds_permission(v_actor, 'organisation.approve') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- FOR UPDATE makes double-approval impossible under concurrency: the second
    -- transaction blocks, then sees status <> 'pending' and refuses.
    SELECT * INTO v_req
    FROM public.organisation_requests r
    WHERE r.id = p_request_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Request not found');
    END IF;

    IF v_req.status <> 'pending' THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Request is already %s', v_req.status));
    END IF;

    v_result := public.create_organisation_internal(
        p_slug        => v_req.slug,
        p_name        => v_req.name,
        p_description => v_req.description,
        p_owner_id    => v_req.requester_id,
        p_domain      => v_req.domain,
        p_visibility  => 'private',
        p_join_policy => 'request_to_join');

    -- Return the inner error verbatim and abort. Because this whole function is
    -- one transaction, nothing the failed call did is committed and the request
    -- stays pending for another attempt.
    IF COALESCE((v_result->>'success')::boolean, false) IS NOT TRUE THEN
        RETURN v_result;
    END IF;

    v_org_id := (v_result->>'org_id')::uuid;

    UPDATE public.organisation_requests
       SET status = 'approved',
           reviewer_id = v_actor,
           reviewed_at = now(),
           review_notes = p_notes,
           created_org_id = v_org_id,
           updated_at = now()
     WHERE id = p_request_id;

    RETURN jsonb_build_object('success', true, 'org_id', v_org_id::text,
        'slug', v_result->>'slug', 'status', 'approved');
END;
$$;

ALTER FUNCTION "public"."approve_organisation_request"("uuid", "text", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."approve_organisation_request"("uuid", "text", "uuid") IS 'Approves a request and creates the organisation in the same transaction. SELECT ... FOR UPDATE makes re-approval impossible under concurrency; a failure inside create_organisation_internal leaves the request pending with nothing half-created.';


CREATE OR REPLACE FUNCTION "public"."reject_organisation_request"(
    "p_request_id" "uuid",
    "p_notes" "text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_status TEXT;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_holds_permission(v_actor, 'organisation.approve') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- Notes are mandatory on rejection: the requester deserves a reason, and it
    -- is the only record of why a name was refused.
    IF p_notes IS NULL OR btrim(p_notes) = '' THEN
        RETURN jsonb_build_object('success', false, 'error', 'A reason is required when rejecting a request');
    END IF;

    SELECT r.status INTO v_status
    FROM public.organisation_requests r WHERE r.id = p_request_id FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Request not found');
    END IF;

    IF v_status <> 'pending' THEN
        RETURN jsonb_build_object('success', false, 'error', format('Request is already %s', v_status));
    END IF;

    UPDATE public.organisation_requests
       SET status = 'rejected', reviewer_id = v_actor, reviewed_at = now(),
           review_notes = p_notes, updated_at = now()
     WHERE id = p_request_id;

    RETURN jsonb_build_object('success', true, 'status', 'rejected');
END;
$$;

ALTER FUNCTION "public"."reject_organisation_request"("uuid", "text", "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."withdraw_organisation_request"(
    "p_request_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_req public.organisation_requests;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    SELECT * INTO v_req
    FROM public.organisation_requests r WHERE r.id = p_request_id FOR UPDATE;

    -- Same answer for "not yours" as for "not found", so this is not a
    -- request-id enumeration oracle.
    IF NOT FOUND OR v_req.requester_id <> v_actor THEN
        RETURN jsonb_build_object('success', false, 'error', 'Request not found');
    END IF;

    IF v_req.status <> 'pending' THEN
        RETURN jsonb_build_object('success', false, 'error', format('Request is already %s', v_req.status));
    END IF;

    UPDATE public.organisation_requests
       SET status = 'withdrawn', updated_at = now()
     WHERE id = p_request_id;

    RETURN jsonb_build_object('success', true, 'status', 'withdrawn');
END;
$$;

ALTER FUNCTION "public"."withdraw_organisation_request"("uuid", "uuid") OWNER TO "postgres";


-- Reviewers see everything; everyone else sees only their own requests. Scoping
-- down rather than erroring means the desktop panel can call this
-- unconditionally and render "your requests" for a non-reviewer.
CREATE OR REPLACE FUNCTION "public"."list_organisation_requests"(
    "p_status" "text" DEFAULT NULL::"text",
    "p_limit" integer DEFAULT 50,
    "p_offset" integer DEFAULT 0,
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_is_reviewer BOOLEAN;
    v_rows JSONB;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    v_is_reviewer := public.user_holds_permission(v_actor, 'organisation.approve');

    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT r.id, r.requester_id, ru.email AS requester_email,
               r.name, r.slug, r.description, r.domain, r.justification,
               r.status, r.reviewer_id, vu.email AS reviewer_email,
               r.review_notes, r.reviewed_at, r.created_org_id, r.created_at
        FROM public.organisation_requests r
        LEFT JOIN auth.users ru ON ru.id = r.requester_id
        LEFT JOIN auth.users vu ON vu.id = r.reviewer_id
        WHERE (v_is_reviewer OR r.requester_id = v_actor)
          AND (p_status IS NULL OR r.status = p_status)
        ORDER BY r.created_at DESC
        LIMIT GREATEST(LEAST(COALESCE(p_limit, 50), 200), 1)
        OFFSET GREATEST(COALESCE(p_offset, 0), 0)
      ) t;

    RETURN jsonb_build_object('success', true, 'is_reviewer', v_is_reviewer, 'data', v_rows);
END;
$$;

ALTER FUNCTION "public"."list_organisation_requests"("text", integer, integer, "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."list_organisation_requests"("text", integer, integer, "uuid") IS 'Organisation-creation requests. Holders of organisation.approve see all; everyone else sees only their own. Returns is_reviewer so the client knows which UI to render.';


-- REVOKE first: 20251023000014_grants.sql sets ALTER DEFAULT PRIVILEGES ... GRANT ALL ON
-- FUNCTIONS TO anon, so every function here is anon-executable the moment it is created
-- and a bare GRANT to authenticated does not take that away. Same trap as ON TABLES,
-- caught there and missed here. None of these were an authorization hole - they all
-- resolve through resolve_org_actor, which returns NULL for anon - but an
-- unauthenticated DB-reachable surface nobody intended is worth closing.
REVOKE EXECUTE ON FUNCTION "public"."submit_organisation_request"("text", "text", "text", "text", "text", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."submit_organisation_request"("text", "text", "text", "text", "text", "uuid") TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."approve_organisation_request"("uuid", "text", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."approve_organisation_request"("uuid", "text", "uuid")                        TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."reject_organisation_request"("uuid", "text", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."reject_organisation_request"("uuid", "text", "uuid")                         TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."withdraw_organisation_request"("uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."withdraw_organisation_request"("uuid", "uuid")                               TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."list_organisation_requests"("text", integer, integer, "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."list_organisation_requests"("text", integer, integer, "uuid")                TO "authenticated", "service_role";


-- ============================================================================
-- SECTION 4: Membership
-- ============================================================================

-- assign_org_member_role_internal: shared by join / approve / invite redemption.
-- Honours organisations.auto_assign_member_role, which is false for the boss
-- organisation (every user is a member there, so a user_roles row per user would
-- just lengthen every JWT for zero extra permissions).
CREATE OR REPLACE FUNCTION "public"."assign_org_member_role_internal"("p_org_id" "uuid", "p_user_id" "uuid")
RETURNS void
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_auto BOOLEAN;
    v_role_id UUID;
BEGIN
    SELECT o.auto_assign_member_role INTO v_auto
    FROM public.organisations o WHERE o.id = p_org_id;

    IF NOT COALESCE(v_auto, false) THEN
        RETURN;
    END IF;

    SELECT orl.role_id INTO v_role_id
    FROM public.organisation_roles orl
    WHERE orl.org_id = p_org_id AND orl.kind = 'user';

    IF v_role_id IS NOT NULL THEN
        INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
        VALUES (p_user_id, v_role_id, NULL, now())
        ON CONFLICT (user_id, role_id) DO NOTHING;
    END IF;
END;
$$;

ALTER FUNCTION "public"."assign_org_member_role_internal"("uuid", "uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."assign_org_member_role_internal"("uuid", "uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."assign_org_member_role_internal"("uuid", "uuid") TO "service_role";


CREATE OR REPLACE FUNCTION "public"."join_organisation"(
    "p_org_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_action TEXT;
    v_join_policy TEXT;
    v_slug TEXT;
    v_source TEXT;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    SELECT o.join_policy, o.slug INTO v_join_policy, v_slug
    FROM public.organisations o WHERE o.id = p_org_id;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    -- Single source of truth, shared with discovery.
    v_action := public.organisation_available_action(v_actor, p_org_id);

    IF v_action = 'member' THEN
        RETURN jsonb_build_object('success', true, 'status', 'active', 'already_member', true);
    ELSIF v_action = 'pending' THEN
        RETURN jsonb_build_object('success', true, 'status', 'pending');
    ELSIF v_action <> 'join' THEN
        RETURN jsonb_build_object('success', false, 'error',
            CASE v_action
                WHEN 'request' THEN 'This organisation requires approval -- use request_organisation_membership'
                ELSE 'This organisation is invite-only'
            END);
    END IF;

    v_source := CASE WHEN v_join_policy = 'open' THEN 'open' ELSE 'domain' END;

    -- In ON CONFLICT DO UPDATE the existing row is referenced by the target
    -- table's bare name (a syntactic alias -- schema-qualifying it is a syntax
    -- error, and search_path is irrelevant). EXCLUDED would be the proposed row.
    INSERT INTO public.organisation_members (org_id, user_id, status, joined_at, join_source)
    VALUES (p_org_id, v_actor, 'active', now(), v_source)
    ON CONFLICT (org_id, user_id) DO UPDATE
        SET status = 'active',
            joined_at = COALESCE(organisation_members.joined_at, now()),
            join_source = COALESCE(organisation_members.join_source, v_source),
            updated_at = now();

    PERFORM public.assign_org_member_role_internal(p_org_id, v_actor);

    RETURN jsonb_build_object('success', true, 'status', 'active',
        'org_id', p_org_id::text, 'slug', v_slug);
END;
$$;

ALTER FUNCTION "public"."join_organisation"("uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."join_organisation"("uuid", "uuid") IS 'Immediate self-join, allowed only when organisation_available_action says "join": an open join policy, or a verified organisation domain matching the caller''s CONFIRMED, non-reserved email domain. Domain-based joining is always an explicit user act -- it never happens silently at signup.';


CREATE OR REPLACE FUNCTION "public"."request_organisation_membership"(
    "p_org_id" "uuid",
    "p_message" "text" DEFAULT NULL::"text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_action TEXT;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT EXISTS (SELECT 1 FROM public.organisations o WHERE o.id = p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    v_action := public.organisation_available_action(v_actor, p_org_id);

    IF v_action = 'member' THEN
        RETURN jsonb_build_object('success', true, 'status', 'active', 'already_member', true);
    ELSIF v_action = 'pending' THEN
        RETURN jsonb_build_object('success', true, 'status', 'pending');
    ELSIF v_action = 'join' THEN
        RETURN jsonb_build_object('success', false, 'error',
            'This organisation can be joined directly -- use join_organisation');
    ELSIF v_action <> 'request' THEN
        RETURN jsonb_build_object('success', false, 'error', 'This organisation is invite-only');
    END IF;

    -- Deliberately assigns NO role. A pending applicant holds no organisation
    -- permissions until an organisation admin approves them.
    INSERT INTO public.organisation_members (
        org_id, user_id, status, requested_at, request_message, join_source
    ) VALUES (p_org_id, v_actor, 'pending', now(), p_message, 'request')
    ON CONFLICT (org_id, user_id) DO NOTHING;

    RETURN jsonb_build_object('success', true, 'status', 'pending');
END;
$$;

ALTER FUNCTION "public"."request_organisation_membership"("uuid", "text", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."request_organisation_membership"("uuid", "text", "uuid") IS 'Applies for membership of a request_to_join organisation. Assigns no role -- a pending applicant holds no organisation permissions. An invite_only organisation refuses outright.';


CREATE OR REPLACE FUNCTION "public"."approve_organisation_member"(
    "p_org_id" "uuid",
    "p_user_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_status TEXT;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- user_is_org_admin, NOT user_holds_permission('organisation.admin'):
    -- the latter is org-blind and would let an admin of one organisation approve
    -- members into another. See the H1 note in 20260801000000.
    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT m.status INTO v_status
    FROM public.organisation_members m
    WHERE m.org_id = p_org_id AND m.user_id = p_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'No membership request found');
    END IF;

    IF v_status = 'active' THEN
        RETURN jsonb_build_object('success', true, 'status', 'active', 'already_member', true);
    END IF;

    UPDATE public.organisation_members
       SET status = 'active', joined_at = now(),
           approved_by = v_actor, approved_at = now(), updated_at = now()
     WHERE org_id = p_org_id AND user_id = p_user_id;

    PERFORM public.assign_org_member_role_internal(p_org_id, p_user_id);

    RETURN jsonb_build_object('success', true, 'status', 'active');
END;
$$;

ALTER FUNCTION "public"."approve_organisation_member"("uuid", "uuid", "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."reject_organisation_member"(
    "p_org_id" "uuid",
    "p_user_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_status TEXT;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT m.status INTO v_status
    FROM public.organisation_members m
    WHERE m.org_id = p_org_id AND m.user_id = p_user_id
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'No membership request found');
    END IF;

    IF v_status = 'active' THEN
        RETURN jsonb_build_object('success', false, 'error',
            'That user is already an active member -- use remove_organisation_member');
    END IF;

    DELETE FROM public.organisation_members
    WHERE org_id = p_org_id AND user_id = p_user_id;

    RETURN jsonb_build_object('success', true, 'status', 'rejected');
END;
$$;

ALTER FUNCTION "public"."reject_organisation_member"("uuid", "uuid", "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."remove_organisation_member"(
    "p_org_id" "uuid",
    "p_user_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_owner UUID;
    v_is_self BOOLEAN;
    v_admin_role_id UUID;
    v_admin_count INTEGER;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    v_is_self := (v_actor = p_user_id);

    IF NOT v_is_self AND NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT o.owner_id INTO v_owner FROM public.organisations o WHERE o.id = p_org_id;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    -- The owner cannot be removed, not even by themselves: organisations.owner_id
    -- is NOT NULL, so an owner-less organisation is unrepresentable. Transfer
    -- ownership first.
    IF p_user_id = v_owner THEN
        RETURN jsonb_build_object('success', false, 'error',
            'The organisation owner cannot be removed -- transfer ownership first');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.organisation_members m
        WHERE m.org_id = p_org_id AND m.user_id = p_user_id
    ) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not a member of this organisation');
    END IF;

    -- Never strip the last administrator: an organisation with no admin can only
    -- be recovered by a global admin.
    SELECT orl.role_id INTO v_admin_role_id
    FROM public.organisation_roles orl
    WHERE orl.org_id = p_org_id AND orl.kind = 'admin';

    IF v_admin_role_id IS NOT NULL AND EXISTS (
        SELECT 1 FROM public.user_roles ur
        WHERE ur.user_id = p_user_id AND ur.role_id = v_admin_role_id
    ) THEN
        SELECT count(*) INTO v_admin_count
        FROM public.user_roles ur
        JOIN public.organisation_members m
          ON m.user_id = ur.user_id AND m.org_id = p_org_id AND m.status = 'active'
        WHERE ur.role_id = v_admin_role_id;

        IF v_admin_count <= 1 THEN
            RETURN jsonb_build_object('success', false, 'error',
                'Cannot remove the last administrator of this organisation');
        END IF;
    END IF;

    DELETE FROM public.organisation_members
    WHERE org_id = p_org_id AND user_id = p_user_id;

    -- CRITICAL: strip every organisation role too. Deleting only the membership
    -- row would leave the user_roles rows behind, so a removed member would keep
    -- this organisation's permissions -- and, through the role hierarchy, its
    -- secret shares.
    DELETE FROM public.user_roles ur
    WHERE ur.user_id = p_user_id
      AND ur.role_id IN (
          SELECT orl.role_id FROM public.organisation_roles orl WHERE orl.org_id = p_org_id
      );

    RETURN jsonb_build_object('success', true, 'removed', p_user_id::text);
END;
$$;

ALTER FUNCTION "public"."remove_organisation_member"("uuid", "uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."remove_organisation_member"("uuid", "uuid", "uuid") IS 'Removes a member (organisation admin) or leaves an organisation (self). Refuses the owner and the last administrator. Deletes the membership row AND every user_roles row for this organisation''s roles -- otherwise a removed member keeps organisation permissions and secret access.';


CREATE OR REPLACE FUNCTION "public"."transfer_organisation_ownership"(
    "p_org_id" "uuid",
    "p_new_owner_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_owner UUID;
    v_admin_role_id UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    SELECT o.owner_id INTO v_owner FROM public.organisations o WHERE o.id = p_org_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    -- Only the current owner or a global admin. An organisation admin who is not
    -- the owner must not be able to seize ownership.
    IF v_owner <> v_actor AND NOT public.is_user_admin(v_actor) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Only the organisation owner can transfer ownership');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.organisation_members m
        WHERE m.org_id = p_org_id AND m.user_id = p_new_owner_id AND m.status = 'active'
    ) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'The new owner must already be an active member of the organisation');
    END IF;

    UPDATE public.organisations
       SET owner_id = p_new_owner_id, updated_at = now()
     WHERE id = p_org_id;

    SELECT orl.role_id INTO v_admin_role_id
    FROM public.organisation_roles orl
    WHERE orl.org_id = p_org_id AND orl.kind = 'admin';

    IF v_admin_role_id IS NOT NULL THEN
        INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
        VALUES (p_new_owner_id, v_admin_role_id, v_actor, now())
        ON CONFLICT (user_id, role_id) DO NOTHING;
    END IF;

    RETURN jsonb_build_object('success', true, 'owner_id', p_new_owner_id::text);
END;
$$;

ALTER FUNCTION "public"."transfer_organisation_ownership"("uuid", "uuid", "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."list_organisation_members"(
    "p_org_id" "uuid",
    "p_status" "text" DEFAULT NULL::"text",
    "p_query" "text" DEFAULT NULL::"text",
    "p_limit" integer DEFAULT 100,
    "p_offset" integer DEFAULT 0,
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_is_admin BOOLEAN;
    v_rows JSONB;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- "Organisation not found", not "Permission denied": a non-member must not be
    -- able to distinguish an organisation they cannot see from one that does not
    -- exist.
    IF NOT public.user_is_org_member(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    v_is_admin := public.user_is_org_admin(v_actor, p_org_id);

    -- A SYSTEM organisation's roster is the entire deployment.
    --
    -- 20260801070000 makes every user an active member of the boss org and
    -- handle_new_user keeps every future signup there, so without this an
    -- ordinary user could page this RPC (limit up to 500, plus an ILIKE on
    -- p_query) and walk out with the email address of every account in the
    -- deployment. public.users deliberately lets a plain user read only their own
    -- row, so that would be a change of posture, not a restatement of one.
    --
    -- The decision: for an is_system organisation a non-admin sees only
    -- themselves. Real organisations are unaffected - their roster is the point,
    -- and their membership is a deliberate act rather than an artefact of signing
    -- up. Pinned by organisation_membership_test.
    IF NOT v_is_admin
       AND EXISTS (SELECT 1 FROM public.organisations o
                    WHERE o.id = p_org_id AND o.is_system) THEN
        RETURN jsonb_build_object(
            'success', true,
            'is_admin', false,
            'system_org_restricted', true,
            'data', COALESCE((
                SELECT jsonb_agg(row_to_json(t)::jsonb)
                FROM (
                    SELECT m.user_id, u.email, m.status, m.joined_at,
                           false AS is_owner, false AS is_admin,
                           ARRAY[]::text[] AS roles
                    FROM public.organisation_members m
                    JOIN auth.users u ON u.id = m.user_id
                    WHERE m.org_id = p_org_id AND m.user_id = v_actor
                ) t), '[]'::jsonb));
    END IF;

    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT m.user_id, u.email, m.status, m.joined_at, m.requested_at,
               m.request_message, m.join_source, m.approved_at,
               (m.user_id = o.owner_id) AS is_owner,
               public.user_is_org_admin(m.user_id, p_org_id) AS is_admin,
               COALESCE((
                   SELECT array_agg(r.name ORDER BY r.name)
                   FROM public.user_roles ur
                   JOIN public.organisation_roles orl
                     ON orl.role_id = ur.role_id AND orl.org_id = p_org_id
                   JOIN public.roles r ON r.id = ur.role_id
                   WHERE ur.user_id = m.user_id
               ), ARRAY[]::text[]) AS roles
        FROM public.organisation_members m
        JOIN public.organisations o ON o.id = m.org_id
        LEFT JOIN auth.users u ON u.id = m.user_id
        WHERE m.org_id = p_org_id
          -- Non-admins see the active roster only. Who has APPLIED is
          -- administrative information.
          AND (v_is_admin OR m.status = 'active')
          AND (p_status IS NULL OR m.status = p_status)
          AND (p_query IS NULL OR btrim(p_query) = '' OR u.email ILIKE '%' || btrim(p_query) || '%')
        ORDER BY (m.user_id = o.owner_id) DESC, m.status, u.email
        LIMIT GREATEST(LEAST(COALESCE(p_limit, 100), 500), 1)
        OFFSET GREATEST(COALESCE(p_offset, 0), 0)
      ) t;

    RETURN jsonb_build_object('success', true, 'is_admin', v_is_admin, 'data', v_rows);
END;
$$;

ALTER FUNCTION "public"."list_organisation_members"("uuid", "text", "text", integer, integer, "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."list_organisation_members"("uuid", "text", "text", integer, integer, "uuid") IS 'Organisation roster. Members see active members; organisation admins additionally see pending and invited rows. Returns "Organisation not found" rather than "Permission denied" to a non-member, so it is not a membership oracle.';


REVOKE EXECUTE ON FUNCTION "public"."join_organisation"("uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."join_organisation"("uuid", "uuid")                                                    TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."request_organisation_membership"("uuid", "text", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."request_organisation_membership"("uuid", "text", "uuid")                              TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."approve_organisation_member"("uuid", "uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."approve_organisation_member"("uuid", "uuid", "uuid")                                  TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."reject_organisation_member"("uuid", "uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."reject_organisation_member"("uuid", "uuid", "uuid")                                   TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."remove_organisation_member"("uuid", "uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."remove_organisation_member"("uuid", "uuid", "uuid")                                   TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."transfer_organisation_ownership"("uuid", "uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."transfer_organisation_ownership"("uuid", "uuid", "uuid")                              TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."list_organisation_members"("uuid", "text", "text", integer, integer, "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."list_organisation_members"("uuid", "text", "text", integer, integer, "uuid")          TO "authenticated", "service_role";


-- ============================================================================
-- SECTION 5: Organisation roles
-- ============================================================================
-- An organisation admin holds NO global role.create / role.assign, so they cannot
-- call create_new_role or assign_role_to_user. These functions are the
-- org-scoped substitutes: each validates that the role belongs to THIS
-- organisation, and create_organisation_role inserts into public.roles directly
-- rather than delegating to create_new_role.
--
-- A newly created organisation role is inert until permissions are attached, and
-- enforce_org_role_permission_scope constrains those to the allowlist.

CREATE OR REPLACE FUNCTION "public"."assign_organisation_role"(
    "p_org_id" "uuid",
    "p_user_id" "uuid",
    "p_role_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- The role must belong to THIS organisation. Without this check an
    -- organisation admin could pass any role id and assign an arbitrary global
    -- role -- including admin.
    IF NOT EXISTS (
        SELECT 1 FROM public.organisation_roles orl
        WHERE orl.org_id = p_org_id AND orl.role_id = p_role_id
    ) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'That role does not belong to this organisation');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.organisation_members m
        WHERE m.org_id = p_org_id AND m.user_id = p_user_id AND m.status = 'active'
    ) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'That user is not an active member of this organisation');
    END IF;

    INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
    VALUES (p_user_id, p_role_id, v_actor, now())
    ON CONFLICT (user_id, role_id) DO NOTHING;

    RETURN jsonb_build_object('success', true);
END;
$$;

ALTER FUNCTION "public"."assign_organisation_role"("uuid", "uuid", "uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."assign_organisation_role"("uuid", "uuid", "uuid", "uuid") IS 'Assigns one of an organisation''s own roles to one of its active members. The org-scoped substitute for assign_role_to_user, which an organisation admin cannot call (no global role.assign). Validates that the role belongs to this organisation, which is what stops an arbitrary global role being assigned.';


CREATE OR REPLACE FUNCTION "public"."remove_organisation_role"(
    "p_org_id" "uuid",
    "p_user_id" "uuid",
    "p_role_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_kind TEXT;
    v_owner UUID;
    v_admin_count INTEGER;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT orl.kind INTO v_kind
    FROM public.organisation_roles orl
    WHERE orl.org_id = p_org_id AND orl.role_id = p_role_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error',
            'That role does not belong to this organisation');
    END IF;

    IF v_kind = 'admin' THEN
        SELECT o.owner_id INTO v_owner FROM public.organisations o WHERE o.id = p_org_id;
        IF p_user_id = v_owner THEN
            RETURN jsonb_build_object('success', false, 'error',
                'The organisation owner must keep the administrator role');
        END IF;

        SELECT count(*) INTO v_admin_count
        FROM public.user_roles ur
        JOIN public.organisation_members m
          ON m.user_id = ur.user_id AND m.org_id = p_org_id AND m.status = 'active'
        WHERE ur.role_id = p_role_id;

        IF v_admin_count <= 1 THEN
            RETURN jsonb_build_object('success', false, 'error',
                'Cannot remove the last administrator of this organisation');
        END IF;
    END IF;

    DELETE FROM public.user_roles
    WHERE user_id = p_user_id AND role_id = p_role_id;

    RETURN jsonb_build_object('success', true);
END;
$$;

ALTER FUNCTION "public"."remove_organisation_role"("uuid", "uuid", "uuid", "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."create_organisation_role"(
    "p_org_id" "uuid",
    "p_suffix" "text",
    "p_description" "text" DEFAULT NULL::"text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_slug TEXT;
    v_max INTEGER;
    v_custom_count INTEGER;
    v_name TEXT;
    v_role_id UUID;
    v_admin_role_id UUID;
    v_user_role_id UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT o.slug, o.max_custom_roles INTO v_slug, v_max
    FROM public.organisations o WHERE o.id = p_org_id;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    IF p_suffix IS NULL OR NOT (p_suffix ~ '^[a-z][a-z0-9_]{1,19}$') THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Role suffix must be 2-20 characters, lowercase letters, digits and underscores, starting with a letter');
    END IF;

    -- 'admin' and 'user' are the reserved kinds -- a custom role must not shadow
    -- the two roles idx_organisation_roles_one_per_kind keys on.
    IF p_suffix IN ('admin', 'user') THEN
        RETURN jsonb_build_object('success', false, 'error',
            '"admin" and "user" are reserved -- the organisation already has those roles');
    END IF;

    v_name := public.organisation_role_name(v_slug, p_suffix);

    -- public.roles.name is validated ^[a-z][a-z0-9_]{2,50}$.
    IF char_length(v_name) < 3 OR char_length(v_name) > 50 THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('The resulting role name "%s" must be 3-50 characters', v_name));
    END IF;

    IF EXISTS (SELECT 1 FROM public.roles r WHERE r.name = v_name) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('A role named "%s" already exists', v_name));
    END IF;

    SELECT count(*) INTO v_custom_count
    FROM public.organisation_roles orl
    WHERE orl.org_id = p_org_id AND orl.kind = 'custom';

    IF v_custom_count >= COALESCE(v_max, 25) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('This organisation has reached its limit of %s custom roles', COALESCE(v_max, 25)));
    END IF;

    -- Direct INSERT rather than create_new_role: this is precisely how an
    -- organisation admin creates a role WITHOUT holding global role.create.
    INSERT INTO public.roles (name, description, is_system)
    VALUES (v_name, COALESCE(p_description, format('Custom role of the "%s" organisation', v_slug)), false)
    RETURNING id INTO v_role_id;

    INSERT INTO public.organisation_roles (org_id, role_id, kind, created_by)
    VALUES (p_org_id, v_role_id, 'custom', v_actor);

    -- Hierarchy is HARD-CODED, never caller-supplied: the custom role sits
    -- strictly between this organisation's admin and user roles. Accepting a
    -- parent/child from the caller would let an organisation admin make the org's
    -- admin role a descendant of their own custom role and inherit its
    -- permissions.
    SELECT orl.role_id INTO v_admin_role_id
    FROM public.organisation_roles orl WHERE orl.org_id = p_org_id AND orl.kind = 'admin';
    SELECT orl.role_id INTO v_user_role_id
    FROM public.organisation_roles orl WHERE orl.org_id = p_org_id AND orl.kind = 'user';

    IF v_admin_role_id IS NOT NULL THEN
        INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
        VALUES (v_admin_role_id, v_role_id) ON CONFLICT DO NOTHING;
    END IF;
    IF v_user_role_id IS NOT NULL THEN
        INSERT INTO public.role_hierarchy (parent_role_id, child_role_id)
        VALUES (v_role_id, v_user_role_id) ON CONFLICT DO NOTHING;
    END IF;

    -- organisation.read so holders can still see the organisation even if this is
    -- their only org role. Passes the Guard 2 allowlist.
    INSERT INTO public.role_permissions (role_id, permission_id)
    SELECT v_role_id, p.id FROM public.permissions p WHERE p.name = 'organisation.read'
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    RETURN jsonb_build_object('success', true, 'role_id', v_role_id::text, 'role_name', v_name);
END;
$$;

ALTER FUNCTION "public"."create_organisation_role"("uuid", "text", "text", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."create_organisation_role"("uuid", "text", "text", "uuid") IS 'Creates a <slug>_<suffix> role owned by an organisation. Inserts into public.roles directly, which is how an organisation admin gets a role without holding global role.create. The hierarchy position is hard-coded between the organisation''s admin and user roles -- never caller-supplied, which would allow inheriting the admin role''s permissions.';


CREATE OR REPLACE FUNCTION "public"."delete_organisation_role"(
    "p_org_id" "uuid",
    "p_role_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_kind TEXT;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    SELECT orl.kind INTO v_kind
    FROM public.organisation_roles orl
    WHERE orl.org_id = p_org_id AND orl.role_id = p_role_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error',
            'That role does not belong to this organisation');
    END IF;

    IF v_kind <> 'custom' THEN
        RETURN jsonb_build_object('success', false, 'error',
            'The organisation''s administrator and member roles cannot be deleted');
    END IF;

    -- Also clear it as a publish role, or the FK's ON DELETE SET NULL would
    -- silently revert the organisation to publish_policy without saying so.
    UPDATE public.organisations
       SET publish_role_id = NULL, updated_at = now()
     WHERE id = p_org_id AND publish_role_id = p_role_id;

    -- FK cascades clear user_roles, role_permissions, role_hierarchy and the
    -- organisation_roles mapping.
    DELETE FROM public.roles WHERE id = p_role_id;

    RETURN jsonb_build_object('success', true);
END;
$$;

ALTER FUNCTION "public"."delete_organisation_role"("uuid", "uuid", "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."grant_organisation_role_permission"(
    "p_org_id" "uuid",
    "p_role_id" "uuid",
    "p_permission_name" "text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_permission_id UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.organisation_roles orl
        WHERE orl.org_id = p_org_id AND orl.role_id = p_role_id
    ) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'That role does not belong to this organisation');
    END IF;

    SELECT p.id INTO v_permission_id
    FROM public.permissions p WHERE p.name = p_permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Permission "%s" not found', p_permission_name));
    END IF;

    IF NOT public.is_org_grantable_permission(v_permission_id) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Permission "%s" cannot be granted to an organisation role', p_permission_name));
    END IF;

    -- Mirrors assign_permission_to_role: you cannot grant what you do not hold.
    IF NOT public.user_holds_permission(v_actor, p_permission_name) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Cannot grant a permission you do not hold ("%s")', p_permission_name));
    END IF;

    -- enforce_org_role_permission_scope is the belt behind this brace.
    INSERT INTO public.role_permissions (role_id, permission_id)
    VALUES (p_role_id, v_permission_id)
    ON CONFLICT (role_id, permission_id) DO NOTHING;

    RETURN jsonb_build_object('success', true);
END;
$$;

ALTER FUNCTION "public"."grant_organisation_role_permission"("uuid", "uuid", "text", "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."revoke_organisation_role_permission"(
    "p_org_id" "uuid",
    "p_role_id" "uuid",
    "p_permission_name" "text",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_permission_id UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM public.organisation_roles orl
        WHERE orl.org_id = p_org_id AND orl.role_id = p_role_id
    ) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'That role does not belong to this organisation');
    END IF;

    SELECT p.id INTO v_permission_id
    FROM public.permissions p WHERE p.name = p_permission_name;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Permission "%s" not found', p_permission_name));
    END IF;

    -- organisation.read is the baseline that makes an organisation visible to its
    -- own members; revoking it would hide the organisation from everyone in it.
    IF p_permission_name = 'organisation.read' THEN
        RETURN jsonb_build_object('success', false, 'error',
            'organisation.read is required by every organisation role');
    END IF;

    DELETE FROM public.role_permissions
    WHERE role_id = p_role_id AND permission_id = v_permission_id;

    RETURN jsonb_build_object('success', true);
END;
$$;

ALTER FUNCTION "public"."revoke_organisation_role_permission"("uuid", "uuid", "text", "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."list_organisation_roles"(
    "p_org_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_rows JSONB;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_member(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT orl.role_id, r.name AS role_name, r.description, orl.kind, orl.created_at,
               (SELECT count(*) FROM public.user_roles ur WHERE ur.role_id = orl.role_id) AS member_count,
               COALESCE((
                   SELECT array_agg(p.name ORDER BY p.name)
                   FROM public.role_permissions rp
                   JOIN public.permissions p ON p.id = rp.permission_id
                   WHERE rp.role_id = orl.role_id
               ), ARRAY[]::text[]) AS permissions
        FROM public.organisation_roles orl
        JOIN public.roles r ON r.id = orl.role_id
        WHERE orl.org_id = p_org_id
        ORDER BY CASE orl.kind WHEN 'admin' THEN 0 WHEN 'user' THEN 1 ELSE 2 END, r.name
      ) t;

    RETURN jsonb_build_object('success', true,
        'is_admin', public.user_is_org_admin(v_actor, p_org_id), 'data', v_rows);
END;
$$;

ALTER FUNCTION "public"."list_organisation_roles"("uuid", "uuid") OWNER TO "postgres";


REVOKE EXECUTE ON FUNCTION "public"."assign_organisation_role"("uuid", "uuid", "uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."assign_organisation_role"("uuid", "uuid", "uuid", "uuid")                  TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."remove_organisation_role"("uuid", "uuid", "uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."remove_organisation_role"("uuid", "uuid", "uuid", "uuid")                  TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."create_organisation_role"("uuid", "text", "text", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."create_organisation_role"("uuid", "text", "text", "uuid")                  TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."delete_organisation_role"("uuid", "uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."delete_organisation_role"("uuid", "uuid", "uuid")                          TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."grant_organisation_role_permission"("uuid", "uuid", "text", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."grant_organisation_role_permission"("uuid", "uuid", "text", "uuid")        TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."revoke_organisation_role_permission"("uuid", "uuid", "text", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."revoke_organisation_role_permission"("uuid", "uuid", "text", "uuid")       TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."list_organisation_roles"("uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."list_organisation_roles"("uuid", "uuid")                                   TO "authenticated", "service_role";


-- ============================================================================
-- SECTION 6: Registered domains
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."add_organisation_domain"(
    "p_org_id" "uuid",
    "p_domain" "text",
    "p_is_primary" boolean DEFAULT false,
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_domain TEXT;
    v_token TEXT;
    v_domain_id UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    v_domain := lower(btrim(COALESCE(p_domain, '')));
    IF v_domain = '' OR NOT (v_domain ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$') THEN
        RETURN jsonb_build_object('success', false, 'error', 'That is not a valid domain name');
    END IF;

    IF EXISTS (SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = v_domain) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('"%s" is a reserved email domain and cannot be claimed by an organisation', v_domain));
    END IF;

    IF EXISTS (SELECT 1 FROM public.organisation_domains d WHERE d.domain = v_domain) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Domain "%s" is already claimed', v_domain));
    END IF;

    v_token := translate(pg_catalog.encode(extensions.gen_random_bytes(24), 'base64'), '+/=', '-_');

    IF p_is_primary THEN
        UPDATE public.organisation_domains SET is_primary = false WHERE org_id = p_org_id;
    END IF;

    INSERT INTO public.organisation_domains (org_id, domain, is_primary, verification_token, created_by)
    VALUES (p_org_id, v_domain, COALESCE(p_is_primary, false), v_token, v_actor)
    RETURNING id INTO v_domain_id;

    RETURN jsonb_build_object(
        'success', true,
        'domain_id', v_domain_id::text,
        'domain', v_domain,
        'verified', false,
        -- Everything the admin needs to publish the proof. Verification itself is
        -- performed by the organisation edge function, the only caller of
        -- mark_organisation_domain_verified.
        'dns_record_type', 'TXT',
        'dns_record_name', '_boss-verify.' || v_domain,
        'dns_record_value', 'boss-org-verification=' || v_token);
END;
$$;

ALTER FUNCTION "public"."add_organisation_domain"("uuid", "text", boolean, "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."add_organisation_domain"("uuid", "text", boolean, "uuid") IS 'Claims an email domain for an organisation, UNVERIFIED, and returns the DNS TXT record to publish. Refuses reserved consumer mailboxes and already-claimed domains.';


CREATE OR REPLACE FUNCTION "public"."set_primary_organisation_domain"(
    "p_domain_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_org_id UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Resolve the organisation from the ROW, then authorize against THAT -- never
    -- against an organisation id the caller supplied. Otherwise an admin of
    -- organisation A could act on organisation B's row by guessing its id.
    SELECT d.org_id INTO v_org_id
    FROM public.organisation_domains d WHERE d.id = p_domain_id;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Domain not found');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, v_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Domain not found');
    END IF;

    -- Only a VERIFIED domain may be primary. routes/domains.ts refuses this too, but that check
    -- is the friendly-error layer: this RPC is GRANTed to authenticated, so an org admin can call
    -- it straight over PostgREST and skip the edge function entirely. The invariant has to live
    -- here or it is not an invariant.
    IF NOT EXISTS (
        SELECT 1 FROM public.organisation_domains d
        WHERE d.id = p_domain_id AND d.verified
    ) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'A domain must be verified before it can be made primary');
    END IF;

    UPDATE public.organisation_domains SET is_primary = false WHERE org_id = v_org_id;
    UPDATE public.organisation_domains SET is_primary = true  WHERE id = p_domain_id;

    RETURN jsonb_build_object('success', true);
END;
$$;

ALTER FUNCTION "public"."set_primary_organisation_domain"("uuid", "uuid") OWNER TO "postgres";


CREATE OR REPLACE FUNCTION "public"."remove_organisation_domain"(
    "p_domain_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_org_id UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    SELECT d.org_id INTO v_org_id
    FROM public.organisation_domains d WHERE d.id = p_domain_id;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Domain not found');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, v_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Domain not found');
    END IF;

    DELETE FROM public.organisation_domains WHERE id = p_domain_id;

    RETURN jsonb_build_object('success', true);
END;
$$;

ALTER FUNCTION "public"."remove_organisation_domain"("uuid", "uuid") OWNER TO "postgres";


-- THE ONLY path that sets verified = true, and it is service_role only.
-- The organisation edge function calls it after resolving the domain row,
-- re-checking user_is_org_admin against THAT row's organisation, confirming the
-- domain is not reserved, and reading a matching TXT record from DNS. There is
-- deliberately no client-callable equivalent.
CREATE OR REPLACE FUNCTION "public"."mark_organisation_domain_verified"(
    "p_domain_id" "uuid",
    "p_verified_by" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_domain TEXT;
BEGIN
    SELECT d.domain INTO v_domain
    FROM public.organisation_domains d WHERE d.id = p_domain_id FOR UPDATE;
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Domain not found');
    END IF;

    -- Belt for the edge function's brace: a reserved domain must never end up
    -- verified, whatever the caller believes it checked.
    IF EXISTS (SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = v_domain) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('"%s" is a reserved email domain', v_domain));
    END IF;

    UPDATE public.organisation_domains
       SET verified = true, verified_at = now(), verified_by = p_verified_by
     WHERE id = p_domain_id;

    RETURN jsonb_build_object('success', true, 'domain', v_domain, 'verified', true);
END;
$$;

ALTER FUNCTION "public"."mark_organisation_domain_verified"("uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."mark_organisation_domain_verified"("uuid", "uuid") IS 'Marks a claimed domain verified. service_role ONLY -- the organisation edge function calls it after a DNS TXT check. Re-checks reserved_email_domains as a backstop. There is no client-callable path that sets verified = true.';


CREATE OR REPLACE FUNCTION "public"."list_organisation_domains"(
    "p_org_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_rows JSONB;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Admin-only: these rows carry verification_token.
    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT d.id AS domain_id, d.domain, d.is_primary, d.verified, d.verified_at,
               'TXT' AS dns_record_type,
               '_boss-verify.' || d.domain AS dns_record_name,
               'boss-org-verification=' || d.verification_token AS dns_record_value,
               d.created_at
        FROM public.organisation_domains d
        WHERE d.org_id = p_org_id
        ORDER BY d.is_primary DESC, d.domain
      ) t;

    RETURN jsonb_build_object('success', true, 'data', v_rows);
END;
$$;

ALTER FUNCTION "public"."list_organisation_domains"("uuid", "uuid") OWNER TO "postgres";


REVOKE EXECUTE ON FUNCTION "public"."add_organisation_domain"("uuid", "text", boolean, "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."add_organisation_domain"("uuid", "text", boolean, "uuid")  TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."set_primary_organisation_domain"("uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."set_primary_organisation_domain"("uuid", "uuid")           TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."remove_organisation_domain"("uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."remove_organisation_domain"("uuid", "uuid")                TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."list_organisation_domains"("uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."list_organisation_domains"("uuid", "uuid")                 TO "authenticated", "service_role";

REVOKE EXECUTE ON FUNCTION "public"."mark_organisation_domain_verified"("uuid", "uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."mark_organisation_domain_verified"("uuid", "uuid") TO "service_role";


-- ============================================================================
-- SECTION 7: Organisation settings
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."update_organisation_settings"(
    "p_org_id" "uuid",
    "p_name" "text" DEFAULT NULL::"text",
    "p_description" "text" DEFAULT NULL::"text",
    "p_visibility" "text" DEFAULT NULL::"text",
    "p_join_policy" "text" DEFAULT NULL::"text",
    "p_publish_policy" "text" DEFAULT NULL::"text",
    "p_publish_role_id" "uuid" DEFAULT NULL::"uuid",
    "p_clear_publish_role" boolean DEFAULT false,
    "p_auto_assign_member_role" boolean DEFAULT NULL::boolean,
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    IF p_visibility IS NOT NULL AND p_visibility NOT IN ('private', 'public') THEN
        RETURN jsonb_build_object('success', false, 'error', 'visibility must be private or public');
    END IF;

    IF p_join_policy IS NOT NULL AND p_join_policy NOT IN ('invite_only', 'request_to_join', 'open') THEN
        RETURN jsonb_build_object('success', false, 'error',
            'join_policy must be invite_only, request_to_join or open');
    END IF;

    IF p_publish_policy IS NOT NULL AND p_publish_policy NOT IN ('owner_only', 'admins', 'members') THEN
        RETURN jsonb_build_object('success', false, 'error',
            'publish_policy must be owner_only, admins or members');
    END IF;

    -- A publish role must be one of THIS organisation's roles, or an organisation
    -- admin could delegate publishing to the holders of an arbitrary global role.
    IF p_publish_role_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM public.organisation_roles orl
        WHERE orl.org_id = p_org_id AND orl.role_id = p_publish_role_id
    ) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'The publish role must belong to this organisation');
    END IF;

    IF p_name IS NOT NULL AND (char_length(btrim(p_name)) < 2 OR char_length(btrim(p_name)) > 100) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Name must be 2-100 characters');
    END IF;

    UPDATE public.organisations
       SET name           = COALESCE(btrim(p_name), name),
           -- NULLIF(p_description, '') is NOT what is wanted here: an empty string is the
           -- caller explicitly CLEARING the description, and COALESCE would then keep the old
           -- one. Empty means empty; only a SQL NULL means leave unchanged.
           description    = CASE WHEN p_description IS NULL THEN description
                                 WHEN btrim(p_description) = '' THEN NULL
                                 ELSE p_description END,
           visibility     = COALESCE(p_visibility, visibility),
           join_policy    = COALESCE(p_join_policy, join_policy),
           publish_policy = COALESCE(p_publish_policy, publish_policy),
           -- COALESCE alone can never set a column back to NULL, so "revert to
           -- publish_policy" needs its own explicit signal.
           publish_role_id = CASE WHEN p_clear_publish_role THEN NULL
                                  ELSE COALESCE(p_publish_role_id, publish_role_id) END,
           auto_assign_member_role = COALESCE(p_auto_assign_member_role, auto_assign_member_role),
           updated_at = now()
     WHERE id = p_org_id;

    RETURN jsonb_build_object('success', true);
END;
$$;

ALTER FUNCTION "public"."update_organisation_settings"("uuid", "text", "text", "text", "text", "text", "uuid", boolean, boolean, "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."update_organisation_settings"("uuid", "text", "text", "text", "text", "text", "uuid", boolean, boolean, "uuid") IS 'Updates an organisation''s mutable settings. NULL means "leave unchanged"; p_clear_publish_role is the explicit signal for setting publish_role_id back to NULL, which COALESCE cannot express. The slug is immutable -- role names derive from it.';

REVOKE EXECUTE ON FUNCTION "public"."update_organisation_settings"("uuid", "text", "text", "text", "text", "text", "uuid", boolean, boolean, "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."update_organisation_settings"("uuid", "text", "text", "text", "text", "text", "uuid", boolean, boolean, "uuid") TO "authenticated", "service_role";


-- ============================================================================
-- SECTION 8: Discovery and "my organisations"
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."get_my_organisations"(
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_rows JSONB;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT o.id, o.slug, o.name, o.description, o.visibility, o.join_policy,
               o.publish_policy, o.is_system,
               m.status, m.joined_at,
               (o.owner_id = v_actor) AS is_owner,
               public.user_is_org_admin(v_actor, o.id) AS is_admin,
               public.user_can_publish_org_plugin(v_actor, o.id) AS can_publish,
               (SELECT count(*) FROM public.organisation_members mm
                 WHERE mm.org_id = o.id AND mm.status = 'active') AS member_count,
               -- AND d.verified: its two siblings (search_organisations,
               -- get_organisation_detail) both filter on it and this one had drifted, so a bare
               -- unverified claim surfaced as the organisation's domain in the desktop list.
               (SELECT d.domain FROM public.organisation_domains d
                 WHERE d.org_id = o.id AND d.is_primary AND d.verified LIMIT 1) AS primary_domain,
               COALESCE((
                   SELECT array_agg(r.name ORDER BY r.name)
                   FROM public.user_roles ur
                   JOIN public.organisation_roles orl
                     ON orl.role_id = ur.role_id AND orl.org_id = o.id
                   JOIN public.roles r ON r.id = ur.role_id
                   WHERE ur.user_id = v_actor
               ), ARRAY[]::text[]) AS my_roles
        FROM public.organisation_members m
        JOIN public.organisations o ON o.id = m.org_id
        WHERE m.user_id = v_actor
        ORDER BY o.is_system DESC, o.name
      ) t;

    RETURN jsonb_build_object('success', true, 'data', v_rows);
END;
$$;

ALTER FUNCTION "public"."get_my_organisations"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_my_organisations"("uuid") IS 'The caller''s organisations, including pending applications, with is_owner / is_admin / can_publish resolved SERVER-SIDE. Per-organisation authority cannot be expressed as a global permission, so the client must never infer it. Also the RPC the Toolbox calls to choose between its Create Organisation and Install Organisation Plugin buttons.';


CREATE OR REPLACE FUNCTION "public"."search_organisations"(
    "p_query" "text" DEFAULT ''::"text",
    "p_limit" integer DEFAULT 20,
    "p_offset" integer DEFAULT 0,
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_email_domain TEXT;
    v_domain_ok BOOLEAN;
    v_query TEXT;
    v_rows JSONB;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    SELECT lower(split_part(u.email, '@', 2)),
           (u.email_confirmed_at IS NOT NULL)
      INTO v_email_domain, v_domain_ok
      FROM auth.users u WHERE u.id = v_actor;

    -- A reserved consumer mailbox never grants discovery of a private
    -- organisation, even if one somehow got it verified.
    v_domain_ok := COALESCE(v_domain_ok, false)
        AND v_email_domain IS NOT NULL AND v_email_domain <> ''
        AND NOT EXISTS (
            SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = v_email_domain);

    v_query := btrim(COALESCE(p_query, ''));

    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT o.id, o.slug, o.name, o.description, o.visibility, o.join_policy,
               (SELECT count(*) FROM public.organisation_members mm
                 WHERE mm.org_id = o.id AND mm.status = 'active') AS member_count,
               (SELECT d.domain FROM public.organisation_domains d
                 WHERE d.org_id = o.id AND d.is_primary AND d.verified LIMIT 1) AS primary_domain,
               public.organisation_available_action(v_actor, o.id) AS available_action,
               (SELECT m.status FROM public.organisation_members m
                 WHERE m.org_id = o.id AND m.user_id = v_actor) AS my_membership_status
        FROM public.organisations o
        WHERE
            -- THE candidate predicate. A private organisation the caller has no
            -- relationship with never reaches the result set at all -- the leak is
            -- closed HERE, not by filtering afterwards. Note that owner_id is
            -- never projected.
            (
                o.visibility = 'public'
                OR EXISTS (SELECT 1 FROM public.organisation_members m
                            WHERE m.org_id = o.id AND m.user_id = v_actor)
                OR (v_domain_ok AND EXISTS (
                        SELECT 1 FROM public.organisation_domains d
                        WHERE d.org_id = o.id AND d.verified AND d.domain = v_email_domain))
            )
            AND (
                v_query = ''
                OR to_tsvector('english'::regconfig, o.name || ' ' || COALESCE(o.description, ''))
                     @@ plainto_tsquery('english'::regconfig, v_query)
                OR o.slug ILIKE '%' || v_query || '%'
                OR o.name ILIKE '%' || v_query || '%'
            )
        ORDER BY o.is_system DESC, o.name
        LIMIT GREATEST(LEAST(COALESCE(p_limit, 20), 100), 1)
        OFFSET GREATEST(COALESCE(p_offset, 0), 0)
      ) t;

    RETURN jsonb_build_object('success', true, 'data', v_rows);
END;
$$;

ALTER FUNCTION "public"."search_organisations"("text", integer, integer, "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."search_organisations"("text", integer, integer, "uuid") IS 'Organisation discovery. Returns public organisations, ones the caller already relates to, and private ones whose VERIFIED domain matches the caller''s CONFIRMED, non-reserved email domain. available_action tells the client which button to render. Never projects owner_id. Granted to authenticated only -- never anon.';

REVOKE EXECUTE ON FUNCTION "public"."get_my_organisations"("uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."get_my_organisations"("uuid")                                TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."search_organisations"("text", integer, integer, "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."search_organisations"("text", integer, integer, "uuid")       TO "authenticated", "service_role";


-- ============================================================================
-- End of File: 20260801030000_organisation_core_rpcs.sql
-- ============================================================================
-- Next Migration: 20260801040000_organisation_invites.sql
-- ============================================================================
