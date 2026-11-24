-- ============================================================================
-- BOSS Database Schema: an organisation website, asked for at request time
--
-- File: 20260807000000_organisation_website.sql
--
-- The request form asks for a website alongside the email domain. They do
-- different jobs and both are wanted: the DOMAIN governs who may join (verified
-- by DNS, it lets matching addresses find and join), the WEBSITE is identity and
-- is only ever displayed.
--
-- SCHEME-RESTRICTED TO http AND https, in the column and again in the RPC. This
-- value is rendered as a link on pages an organisation's members read, and it is
-- filled in by any authenticated user submitting a request - so a javascript: or
-- data: URL here would be stored XSS with a self-service entry point. The page
-- renderer refuses those schemes as well; this is the half that stops one being
-- stored in the first place.
--
-- ADDING A DEFAULTED PARAMETER IS NOT A REPLACEMENT. CREATE OR REPLACE matches on
-- the argument list, so adding p_website to submit_organisation_request or
-- create_organisation_internal creates a SECOND function rather than replacing the
-- first, and a call with the old argument count then fails as ambiguous. Those two
-- are dropped and recreated, and their REVOKE/GRANT re-applied, because DROP takes
-- the grants with it. approve_organisation_request, update_organisation_settings,
-- get_organisation_detail and get_my_organisations keep their identity where the
-- signature is unchanged.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- Columns
-- ---------------------------------------------------------------------------
ALTER TABLE "public"."organisation_requests"
    ADD COLUMN IF NOT EXISTS "website" "text";

ALTER TABLE "public"."organisations"
    ADD COLUMN IF NOT EXISTS "website" "text";

-- The CHECK is the backstop the RPC's friendlier message sits in front of. NULL is
-- allowed (a website is optional); an empty string is not, so "unset" has exactly
-- one representation and a caller cannot store '' and read it back as a link.
ALTER TABLE "public"."organisation_requests"
    DROP CONSTRAINT IF EXISTS "organisation_requests_website_check";
ALTER TABLE "public"."organisation_requests"
    ADD CONSTRAINT "organisation_requests_website_check"
    CHECK ("website" IS NULL OR ("website" ~* '^https?://[^[:space:]]+$' AND length("website") <= 500));

ALTER TABLE "public"."organisations"
    DROP CONSTRAINT IF EXISTS "organisations_website_check";
ALTER TABLE "public"."organisations"
    ADD CONSTRAINT "organisations_website_check"
    CHECK ("website" IS NULL OR ("website" ~* '^https?://[^[:space:]]+$' AND length("website") <= 500));

COMMENT ON COLUMN "public"."organisations"."website" IS 'Public website, http/https only, displayed as a link on the organisation pages. Distinct from organisation_domains, which governs who may join; this is identity and grants nothing.';
COMMENT ON COLUMN "public"."organisation_requests"."website" IS 'Website supplied with the request, carried onto the organisation on approval.';


-- ---------------------------------------------------------------------------
-- submit_organisation_request: gains p_website (signature change -> drop first)
-- ---------------------------------------------------------------------------
DROP FUNCTION IF EXISTS "public"."submit_organisation_request"("text", "text", "text", "text", "text", "uuid");

CREATE OR REPLACE FUNCTION "public"."submit_organisation_request"(
    "p_name" "text",
    "p_slug" "text",
    "p_description" "text" DEFAULT NULL::"text",
    "p_domain" "text" DEFAULT NULL::"text",
    "p_justification" "text" DEFAULT NULL::"text",
    "p_website" "text" DEFAULT NULL::"text",
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
    v_website TEXT;
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

    -- Validated HERE as well as by the column CHECK, so a typo comes back as a
    -- sentence rather than a constraint violation the caller cannot read.
    --
    -- http and https only. This value is rendered as a link on the organisation
    -- pages, and a javascript: or data: URL there would be script execution from a
    -- field any authenticated user can fill in. The renderer refuses those too;
    -- this is the half that stops one being stored at all.
    IF p_website IS NOT NULL AND btrim(p_website) <> '' THEN
        v_website := btrim(p_website);
        IF v_website !~* '^https?://[^[:space:]]+$' THEN
            RETURN jsonb_build_object('success', false, 'error',
                'Website must be a full http:// or https:// address');
        END IF;
        IF length(v_website) > 500 THEN
            RETURN jsonb_build_object('success', false, 'error',
                'Website must be 500 characters or fewer');
        END IF;
    END IF;

    BEGIN
        INSERT INTO public.organisation_requests (
            requester_id, name, slug, description, domain, justification, website, status
        ) VALUES (
            v_actor, btrim(p_name), p_slug, p_description, v_domain, p_justification, v_website, 'pending'
        ) RETURNING id INTO v_request_id;
    EXCEPTION WHEN unique_violation THEN
        -- idx_organisation_requests_pending_slug
        RETURN jsonb_build_object('success', false, 'error',
            format('A pending request for slug "%s" already exists', p_slug));
    END;

    RETURN jsonb_build_object('success', true, 'request_id', v_request_id::text, 'status', 'pending');
END;
$$;

ALTER FUNCTION "public"."submit_organisation_request"("text", "text", "text", "text", "text", "text", "uuid") OWNER TO "postgres";
REVOKE EXECUTE ON FUNCTION "public"."submit_organisation_request"("text", "text", "text", "text", "text", "text", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."submit_organisation_request"("text", "text", "text", "text", "text", "text", "uuid") TO "authenticated", "service_role";
COMMENT ON FUNCTION "public"."submit_organisation_request"("text", "text", "text", "text", "text", "text", "uuid") IS 'Submits an organisation-creation request. Gated on the global organisation.create permission; revoking that from the "user" role disables self-service organisation creation platform-wide. Also called by the Toolbox Create Organisation dialog.';


-- ---------------------------------------------------------------------------
-- create_organisation_internal: gains p_website (signature change -> drop first)
-- ---------------------------------------------------------------------------
DROP FUNCTION IF EXISTS "public"."create_organisation_internal"("text", "text", "text", "uuid", "text", "text", "text", boolean, "text", "text", boolean);

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
    "p_auto_assign_member_role" boolean DEFAULT true,
    "p_website" "text" DEFAULT NULL::"text"
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
            owner_id, created_by, is_system, auto_assign_member_role, website
        ) VALUES (
            p_slug, btrim(p_name), p_description, p_visibility, p_join_policy,
            p_owner_id, p_owner_id, p_is_system, p_auto_assign_member_role,
            NULLIF(btrim(COALESCE(p_website, '')), '')
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

ALTER FUNCTION "public"."create_organisation_internal"("text", "text", "text", "uuid", "text", "text", "text", boolean, "text", "text", boolean, "text") OWNER TO "postgres";
REVOKE EXECUTE ON FUNCTION "public"."create_organisation_internal"("text", "text", "text", "uuid", "text", "text", "text", boolean, "text", "text", boolean, "text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."create_organisation_internal"("text", "text", "text", "uuid", "text", "text", "text", boolean, "text", "text", boolean, "text") TO "service_role";


-- ---------------------------------------------------------------------------
-- Same signature, new body. CREATE OR REPLACE keeps the grants.
-- ---------------------------------------------------------------------------

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
        p_website     => v_req.website,
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


-- update_organisation_settings gains a parameter, so it too must be dropped.
DROP FUNCTION IF EXISTS "public"."update_organisation_settings"("uuid", "text", "text", "text", "text", "text", "uuid", boolean, boolean, "uuid");

CREATE OR REPLACE FUNCTION "public"."update_organisation_settings"(
    "p_org_id" "uuid",
    "p_name" "text" DEFAULT NULL::"text",
    "p_description" "text" DEFAULT NULL::"text",
    "p_visibility" "text" DEFAULT NULL::"text",
    "p_join_policy" "text" DEFAULT NULL::"text",
    "p_publish_policy" "text" DEFAULT NULL::"text",
    "p_publish_role_id" "uuid" DEFAULT NULL::"uuid",
    "p_clear_publish_role" boolean DEFAULT false,
    "p_website" "text" DEFAULT NULL::"text",
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
           -- Same empty-means-empty rule the description uses: an empty string
           -- CLEARS the website, and only a SQL NULL leaves it alone.
           website        = CASE WHEN p_website IS NULL THEN website
                                 WHEN btrim(p_website) = '' THEN NULL
                                 ELSE btrim(p_website) END,
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

ALTER FUNCTION "public"."update_organisation_settings"("uuid", "text", "text", "text", "text", "text", "uuid", boolean, "text", boolean, "uuid") OWNER TO "postgres";
REVOKE EXECUTE ON FUNCTION "public"."update_organisation_settings"("uuid", "text", "text", "text", "text", "text", "uuid", boolean, "text", boolean, "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."update_organisation_settings"("uuid", "text", "text", "text", "text", "text", "uuid", boolean, "text", boolean, "uuid") TO "authenticated", "service_role";


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
        'website',      v_org.website,
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
        SELECT o.id, o.slug, o.name, o.description, o.website, o.visibility, o.join_policy,
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
