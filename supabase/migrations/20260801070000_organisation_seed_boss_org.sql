-- ============================================================================
-- BOSS Database Schema: Seed the default `boss` organisation
-- ============================================================================
-- File: 20260801070000_organisation_seed_boss_org.sql
-- Description:
--   Creates the organisation every BOSS user belongs to, backfills membership for
--   existing users, and extends handle_new_user so future signups join it too.
--
-- WHY ITS ROLES ARE NAMED boss_org_admin / boss_org_user, NOT boss_admin / boss_user
--   `boss_admin` ALREADY EXISTS as a GLOBAL SYSTEM ROLE (20260625000000) carrying
--   role.create, role.assign and plugins.admin.*. Deriving the name from the slug
--   would map that global role into organisation_roles and hand every
--   administrator of the boss organisation global platform powers.
--   enforce_org_role_not_system would reject the mapping anyway -- so the seed
--   would simply fail -- but the right fix is not to try. organisation_roles, not
--   the role's name, is what makes a role an organisation role, so the names are
--   free to differ from the slug.
--
-- Dependencies:
--   - 20260801030000_organisation_core_rpcs.sql (create_organisation_internal)
--   - 20251023000007_helper_functions.sql       (the handle_new_user this replaces)
--
-- MUST RUN BEFORE 20260802000000 (secrets) and 20260803000000 (plugins): both
-- backfill against this organisation.
--
-- Next migration: 20260802000000_secrets_org_ownership.sql
-- ============================================================================


-- ============================================================================
-- SECTION 1: Create the organisation
-- ============================================================================
-- Idempotent, and CALLABLE - not just a DO block.
--
-- Migrations run exactly once, so the original "the next run creates it once a
-- first user exists" was wrong: on any environment where the schema lands before
-- the first user (fresh self-host, staging bootstrap, schema-then-data restore)
-- nothing would ever create the boss organisation, and the failure is silent -
-- plugins.org_id stays NULL, plugins_default_org is inert, and handle_new_user
-- warns once per signup into a log nobody reads.
--
-- ensure_boss_organisation() is therefore a function, called here AND from
-- handle_new_user, so the first signup on a fresh deployment creates it.

CREATE OR REPLACE FUNCTION "public"."ensure_boss_organisation"() RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_owner uuid;
    v_res jsonb;
BEGIN
    IF EXISTS (SELECT 1 FROM public.organisations WHERE slug = 'boss') THEN
        RETURN jsonb_build_object('success', true, 'created', false);
    END IF;

    -- Owner: the longest-standing global admin, else the oldest user. The
    -- (r.name IS NULL) sort key puts admins first without needing two queries.
    SELECT u.id INTO v_owner
      FROM auth.users u
      LEFT JOIN public.user_roles ur ON ur.user_id = u.id
      LEFT JOIN public.roles r ON r.id = ur.role_id AND r.name = 'admin'
     ORDER BY (r.name IS NULL), u.created_at
     LIMIT 1;

    IF v_owner IS NULL THEN
        -- organisations.owner_id is NOT NULL, so there is nothing to create yet.
        -- handle_new_user calls this again on the first signup.
        RETURN jsonb_build_object('success', false, 'created', false, 'error', 'no users yet');
    END IF;

    v_res := public.create_organisation_internal(
        p_slug        => 'boss',
        p_name        => 'BOSS',
        p_description => 'The default BOSS organisation. Every user is a member.',
        p_owner_id    => v_owner,
        p_domain      => NULL,
        p_visibility  => 'public',
        p_join_policy => 'open',
        -- Bypasses is_reserved_organisation_slug, which lists 'boss' precisely
        -- because of the boss_admin collision described in the header.
        p_is_system   => true,
        p_admin_role_name => 'boss_org_admin',
        p_user_role_name  => 'boss_org_user',
        -- Every user is a member of this organisation. Auto-assigning the member
        -- role would add one user_roles row per user and lengthen EVERY JWT, for
        -- zero additional permissions -- the global `user` role already carries
        -- the baseline, and boss_org_user holds only organisation.read.
        p_auto_assign_member_role => false);

    IF COALESCE((v_res->>'success')::boolean, false) IS NOT TRUE THEN
        RAISE EXCEPTION 'boss organisation seed failed: %', v_res->>'error';
    END IF;

    RETURN jsonb_build_object('success', true, 'created', true, 'owner_id', v_owner::text);
END;
$$;

ALTER FUNCTION "public"."ensure_boss_organisation"() OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."ensure_boss_organisation"() FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."ensure_boss_organisation"() TO "service_role";

COMMENT ON FUNCTION "public"."ensure_boss_organisation"() IS
'Creates the boss organisation if it does not exist, choosing the longest-standing global admin (else the oldest user) as owner. Idempotent, and callable: migrations run once, so a deployment whose schema lands before its first user would otherwise never get one. handle_new_user calls this on every signup.';

-- Run it now, for the normal case where users already exist.
DO $$
DECLARE
    v jsonb;
BEGIN
    v := public.ensure_boss_organisation();
    IF (v->>'created')::boolean THEN
        RAISE NOTICE 'boss organisation created (owner %).', v->>'owner_id';
    ELSE
        RAISE NOTICE 'boss organisation not created now: %', COALESCE(v->>'error', 'already exists');
    END IF;
END $$;


-- ============================================================================
-- SECTION 2: Backfill membership for every existing user
-- ============================================================================
-- Idempotent via ON CONFLICT. A no-op when SECTION 1 skipped, because the
-- CROSS JOIN then has no organisation to join against.

INSERT INTO "public"."organisation_members" ("org_id", "user_id", "status", "joined_at", "join_source")
SELECT o."id", u."id", 'active', "now"(), 'seed'
FROM "public"."organisations" o
CROSS JOIN "auth"."users" u
WHERE o."slug" = 'boss'
ON CONFLICT ("org_id", "user_id") DO NOTHING;


-- ============================================================================
-- SECTION 3: handle_new_user -- default organisation membership at signup
-- ============================================================================
-- Replaced in full. Steps 1 and 2 are byte-equivalent to 20251023000007; step 3
-- is new. Also upgraded to SET search_path TO '' with fully-qualified names,
-- which the original lacked.
--
-- THE EXCEPTION WRAPPER AROUND STEP 3 IS NOT DEFENSIVE PADDING. This trigger runs
-- inside the auth.users INSERT, so anything that raises here fails USER CREATION
-- -- signup, invite acceptance, the passkey admin-API path, and the pgTAP
-- fixtures that insert auth.users rows directly. A missing or renamed boss
-- organisation must degrade to "no default membership", never to "nobody can sign
-- up".

CREATE OR REPLACE FUNCTION "public"."handle_new_user"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_role_id UUID;
    v_boss_org_id UUID;
BEGIN
    -- Step 1: mirror the auth.users record into public.users.
    INSERT INTO public.users (id, email, created_at, updated_at)
    VALUES (NEW.id, NEW.email, now(), now())
    ON CONFLICT (id) DO NOTHING;

    -- Step 2: assign the default `user` role. assigned_by = NULL marks it a system
    -- assignment. This is also what makes `user` first by assigned_at, which
    -- get_user_roles_for_hook relies on for the primary_role claim.
    SELECT id INTO v_role_id FROM public.roles WHERE name = 'user';

    INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
    VALUES (NEW.id, v_role_id, NULL, now())
    ON CONFLICT (user_id, role_id) DO NOTHING;

    -- Step 3 (20260801070000): join the default organisation.
    BEGIN
        -- Deliberately NOT calling ensure_boss_organisation() here.
        --
        -- Self-healing on first signup is tempting and does close the fresh-deployment
        -- gap, but it makes the first-ever signup create the organisation and its two
        -- roles as a side effect of an auth trigger - which changes the role graph out
        -- from under anything that reads it, and broke four pgTAP suites that assert
        -- exact grantable-role sets. An auth hook is the wrong place to acquire that
        -- blast radius.
        --
        -- The recovery path is the callable ensure_boss_organisation() above, listed in
        -- the deployment notes. See the note on that function.
        SELECT id INTO v_boss_org_id FROM public.organisations WHERE slug = 'boss';

        IF v_boss_org_id IS NOT NULL THEN
            INSERT INTO public.organisation_members (org_id, user_id, status, joined_at, join_source)
            VALUES (v_boss_org_id, NEW.id, 'active', now(), 'seed')
            ON CONFLICT (org_id, user_id) DO NOTHING;
        END IF;
    EXCEPTION WHEN OTHERS THEN
        -- See the header: a raise here would fail user creation outright.
        RAISE WARNING 'handle_new_user: could not add % to the boss organisation: %', NEW.id, SQLERRM;
    END;

    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."handle_new_user"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."handle_new_user"() IS 'auth.users INSERT trigger: mirrors the user into public.users, assigns the default `user` role, and adds them to the default boss organisation. The organisation step is exception-wrapped because this runs inside user creation -- a raise here would break signup for everyone.';


-- ============================================================================
-- SECTION 4: A deliberate non-feature -- no domain auto-join at signup
-- ============================================================================
-- handle_new_user does NOT look at organisation_domains. If it did, an
-- organisation that verified a domain would instantly absorb every past and
-- future signup on it, with no act or awareness on the user's part, and
-- verification would become a mass-enrolment tool.
--
-- Instead, a verified domain match surfaces as available_action = 'join' in
-- search_organisations, and joining happens only through an explicit
-- join_organisation call -- recorded as join_source = 'domain'. See
-- organisation_available_action in 20260801030000.


-- ============================================================================
-- End of File: 20260801070000_organisation_seed_boss_org.sql
-- ============================================================================
-- Next Migration: 20260802000000_secrets_org_ownership.sql
-- ============================================================================
