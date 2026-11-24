-- ============================================================================
-- BOSS Database Schema: validate the website on every write path
--
-- File: 20260808000000_organisation_website_validation.sql
--
-- 20260807000000's header claimed the scheme restriction was applied "in the
-- column and again in the RPC". That was true of submit_organisation_request and
-- NOT of update_organisation_settings - which is the path an administrator
-- actually uses, while submit is the one a person uses once.
--
-- The consequence was worse than a missing message. The only remaining guard was
-- organisations_website_check, which raises 23514 and ABORTS THE WHOLE UPDATE, so
-- a mistyped website silently discarded the name, description, visibility, join
-- policy, publish policy and the auto-assign checkbox in the same submission -
-- and the page could only render "The change was refused", naming no field.
-- Reproduced before fixing: typing "zed.example" into the settings form raised
-- the constraint out of the function rather than returning an error object.
--
-- The check now lives in one place both RPCs call, so the two cannot drift again,
-- and create_organisation_internal re-checks on the write path for the same
-- reason the domain right above it does.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- One definition of what a website may be
-- ---------------------------------------------------------------------------
-- Returns the message to show, or NULL when the value is acceptable. NULL input
-- is acceptable: the field is optional everywhere.
--
-- IMMUTABLE and STRICT-free on purpose - it must return NULL for NULL input
-- rather than being skipped, so callers can pass an unset value straight through.
CREATE OR REPLACE FUNCTION "public"."validate_website"("p_website" "text")
RETURNS "text"
    LANGUAGE "plpgsql" IMMUTABLE
    SET "search_path" TO ''
    AS $$
BEGIN
    IF p_website IS NULL OR btrim(p_website) = '' THEN
        RETURN NULL;
    END IF;
    -- http and https only. This value is rendered as a link on pages an
    -- organisation's members read and is supplied by any authenticated user on a
    -- request, before review, so a javascript: or data: URL here would be stored
    -- XSS with a self-service entry point.
    IF btrim(p_website) !~* '^https?://[^[:space:]]+$' THEN
        RETURN 'Website must be a full http:// or https:// address';
    END IF;
    IF length(btrim(p_website)) > 500 THEN
        RETURN 'Website must be 500 characters or fewer';
    END IF;
    RETURN NULL;
END;
$$;

ALTER FUNCTION "public"."validate_website"("text") OWNER TO "postgres";
-- Every other helper in this schema pairs the OWNER with an explicit REVOKE and
-- GRANT (organisation_role_name and is_reserved_organisation_slug both do in
-- 20260801010000). Without them this defaults to PUBLIC and is anon-callable
-- through PostgREST. The impact is nil - it is pure and touches no data - but the
-- convention is what makes an exception visible.
REVOKE EXECUTE ON FUNCTION "public"."validate_website"("text") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."validate_website"("text") TO "authenticated", "service_role";
COMMENT ON FUNCTION "public"."validate_website"("text") IS 'Returns the error message for an unacceptable organisation website, or NULL. Shared by submit_organisation_request, update_organisation_settings and create_organisation_internal so the rule cannot drift between the path a requester uses and the one an administrator uses.';


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
    v_website_error TEXT;
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
    v_website := NULLIF(btrim(COALESCE(p_website, '')), '');
    v_website_error := public.validate_website(v_website);
    IF v_website_error IS NOT NULL THEN
        RETURN jsonb_build_object('success', false, 'error', v_website_error);
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
    v_website_error TEXT;
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

    -- Checked BEFORE the UPDATE. Without this the only guard was the column CHECK,
    -- which raises 23514 and aborts the WHOLE statement - so a mistyped website
    -- silently discarded the name, description, visibility, join policy, publish
    -- policy and the auto-assign checkbox with it, and the page could only say
    -- "the change was refused". This is the path an administrator actually uses;
    -- submit_organisation_request is the one a person uses once.
    v_website_error := public.validate_website(NULLIF(btrim(COALESCE(p_website, '')), ''));
    IF v_website_error IS NOT NULL THEN
        RETURN jsonb_build_object('success', false, 'error', v_website_error);
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


-- ---------------------------------------------------------------------------
-- create_organisation_internal re-checks on the write path
-- ---------------------------------------------------------------------------
-- The header above claimed this and the first version did not do it. Same
-- signature as 20260807000000, so CREATE OR REPLACE keeps the grants.

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
    v_website TEXT;
    v_website_error TEXT;
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

    -- Re-checked on the write path, for the same reason the domain below is: this
    -- function is service_role-callable directly, and its own comment says that is
    -- the supported use. Today every caller passes a value that already cleared an
    -- identical CHECK on organisation_requests, but that is a coincidence of two
    -- textually identical predicates - and without this, a direct caller gets a
    -- raw 23514 propagating out of approve_organisation_request as an unhandled
    -- exception instead of the {success:false,error} object every other refusal
    -- there returns.
    v_website := NULLIF(btrim(COALESCE(p_website, '')), '');
    v_website_error := public.validate_website(v_website);
    IF v_website_error IS NOT NULL THEN
        RETURN jsonb_build_object('success', false, 'error', v_website_error);
    END IF;

    -- 1. The organisation.
    BEGIN
        INSERT INTO public.organisations (
            slug, name, description, visibility, join_policy,
            owner_id, created_by, is_system, auto_assign_member_role, website
        ) VALUES (
            p_slug, btrim(p_name), p_description, p_visibility, p_join_policy,
            p_owner_id, p_owner_id, p_is_system, p_auto_assign_member_role,
            v_website
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


-- ---------------------------------------------------------------------------
-- An approver must be able to see the website they are approving
-- ---------------------------------------------------------------------------
-- The whole threat model here is that any authenticated user supplies this value
-- and it ends up as a link on a page other members read. Review is the control
-- that stands between those two facts, and the queue was not showing the field.

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
               r.name, r.slug, r.description, r.domain, r.website, r.justification,
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


-- ---------------------------------------------------------------------------
-- Comments DROP FUNCTION removed in 20260807000000
-- ---------------------------------------------------------------------------
-- DROP takes the COMMENT with it as well as the grants. 20260807000000 re-applied
-- the grants and re-added submit_organisation_request's comment, and silently lost
-- these two - including the "service_role ONLY" warning, which is the one a reader
-- most needs.
COMMENT ON FUNCTION "public"."create_organisation_internal"("text", "text", "text", "uuid", "text", "text", "text", boolean, "text", "text", boolean, "text") IS 'Creates an organisation with its two roles, the founder membership and an optional unverified domain. service_role ONLY: it accepts an arbitrary owner id and performs no authorisation of its own, so every caller must have established the actor first.';

COMMENT ON FUNCTION "public"."update_organisation_settings"("uuid", "text", "text", "text", "text", "text", "uuid", boolean, "text", boolean, "uuid") IS 'Updates an organisation''s settings. Organisation admins only, re-derived server-side. An empty string CLEARS description and website; a SQL NULL leaves them unchanged.';
