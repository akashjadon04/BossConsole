-- ============================================================================
-- BOSS Database Schema: give boss organisation members the user-kind role
--
-- File: 20260806000000_boss_org_member_role.sql
--
-- The seed created the boss organisation with auto_assign_member_role = false,
-- reasoning that every user is a member so a user_roles row per user would
-- lengthen every JWT for zero extra permissions - the global `user` role already
-- grants organisation.read, which is the only permission boss_org_user carries.
--
-- That reasoning is still true and is being overridden deliberately: the operator
-- wants the roster to show an explicit organisation role for every member rather
-- than a blank, and wants membership of the organisation to be legible from a
-- user's roles alone.
--
-- WHAT THIS COSTS, stated so it is not rediscovered later. Every active member
-- gains a user_roles row, and organisation role names ride in the existing
-- `user_roles` JWT claim (20260801060000 omitted a separate org_roles claim for
-- exactly that reason), so every token on the deployment grows by one entry. No
-- permission changes for anybody.
--
-- Four parts, because flipping the flag alone would not have worked:
--   1. auto_assign_member_role = true for the boss organisation
--   2. backfill the role onto existing ACTIVE members
--   3. the same default inside ensure_boss_organisation(), which creates the
--      organisation on a fresh deployment AFTER this migration runs - so the
--      UPDATE alone would be silently undone
--   4. teach handle_new_user to assign it, since that trigger inserts the
--      membership row directly and never called assign_org_member_role_internal
-- ============================================================================


-- 1. The flag. Scoped by slug AND is_system so a non-system organisation that
--    happens to be called "boss" cannot be caught by this.
UPDATE "public"."organisations"
   SET "auto_assign_member_role" = true
 WHERE "slug" = 'boss' AND "is_system";


-- 2. Backfill.
--
-- ACTIVE members only. A pending or invited row is a request that has not been
-- accepted, and granting it a role would hand out organisation standing that the
-- approval step exists to gate.
--
-- assigned_by is NULL, which is how this schema marks a system assignment - the
-- same convention handle_new_user uses for the global `user` role.
--
-- ON CONFLICT DO NOTHING makes it idempotent, so a re-run (or a re-applied
-- migration on a fresh environment where the seed already did the work) is inert.
INSERT INTO "public"."user_roles" ("user_id", "role_id", "assigned_by", "assigned_at")
SELECT m."user_id", orl."role_id", NULL, now()
  FROM "public"."organisation_members" m
  JOIN "public"."organisations" o
    ON o."id" = m."org_id" AND o."slug" = 'boss' AND o."is_system"
  JOIN "public"."organisation_roles" orl
    ON orl."org_id" = o."id" AND orl."kind" = 'user'
 WHERE m."status" = 'active'
ON CONFLICT ("user_id", "role_id") DO NOTHING;


-- 3. The creator, so a fresh deployment does not undo part 1.
--
-- ensure_boss_organisation() hardcoded false, and it is what creates the
-- organisation on a fresh environment and on the documented recovery path. Since
-- it runs AFTER this migration in both cases, the UPDATE above would be overwritten
-- and the flag would silently be false again. Found by running a db reset rather
-- than by reading: the UPDATE reported success against zero rows, because the
-- organisation does not exist yet in a database with no users.
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
        -- true since 20260806000000. The original false was correct on its own
        -- terms (every user is a member, so the role adds a user_roles row and a
        -- JWT entry for zero extra permissions), and was overridden deliberately so
        -- that membership is visible from a user's roles. Changed HERE and not only
        -- by an UPDATE, because this function recreates the organisation on a fresh
        -- deployment and on the documented recovery path - an UPDATE alone reverts
        -- the moment the organisation is created after the migration has run, which
        -- is exactly what a local db reset does.
        p_auto_assign_member_role => true);

    IF COALESCE((v_res->>'success')::boolean, false) IS NOT TRUE THEN
        RAISE EXCEPTION 'boss organisation seed failed: %', v_res->>'error';
    END IF;

    RETURN jsonb_build_object('success', true, 'created', true, 'owner_id', v_owner::text);
END;
$$;

ALTER FUNCTION "public"."ensure_boss_organisation"() OWNER TO "postgres";


-- 4. The signup trigger.
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

            -- The membership row alone does not carry the organisation's user-kind
            -- role. Every other entry path (join, approve, invite redemption) goes
            -- through assign_org_member_role_internal; this trigger inserted the row
            -- directly and so was the one path that did not, which is why turning
            -- auto_assign_member_role on for the boss organisation would otherwise
            -- have changed nothing for a new signup.
            --
            -- The helper is called rather than the INSERT inlined: it reads
            -- auto_assign_member_role itself, so switching the flag back off stops
            -- this immediately, and there is one definition of what joining assigns.
            --
            -- Note this ASSIGNS an existing role; it does not create one. The header
            -- note above rules out creating the organisation and its roles here, for
            -- a different reason - that changes the role graph out from under readers.
            PERFORM public.assign_org_member_role_internal(v_boss_org_id, NEW.id);
        END IF;
    EXCEPTION WHEN OTHERS THEN
        -- See the header: a raise here would fail user creation outright.
        RAISE WARNING 'handle_new_user: could not add % to the boss organisation: %', NEW.id, SQLERRM;
    END;

    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."handle_new_user"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."handle_new_user"() IS 'auth.users INSERT trigger: mirrors the user into public.users, assigns the default `user` role, adds them to the default boss organisation and assigns that organisation''s user-kind role when the organisation opts in. The organisation step is exception-wrapped because this runs inside user creation -- a raise here would break signup for everyone.';
