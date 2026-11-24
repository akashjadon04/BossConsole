-- ============================================================================
-- BOSS Database Schema: break the assigned_at tie the primary_role claim rides on
--
-- File: 20260806010000_org_member_role_assigned_at.sql
--
-- Follows 20260806000000, which taught handle_new_user to assign the boss
-- organisation's user-kind role. Both inserts used now(), which is
-- transaction_timestamp() and constant across the transaction regardless of
-- nesting, so a new signup got two user_roles rows with the SAME assigned_at.
--
-- get_user_roles_for_hook aggregates ORDER BY assigned_at and the access-token
-- hook takes element 1 as primary_role. Sorts are not stable, so the tie makes
-- that claim plan-dependent - a signup could report boss_org_user as its role.
--
-- Verified on a local database before this fix: two rows, one distinct
-- assigned_at value.
--
-- Existing users are unaffected: their `user` row predates the backfill by a real
-- interval, so there is no tie to break.
-- ============================================================================


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
        -- clock_timestamp(), NOT now(). now() is transaction_timestamp() and is
        -- constant for the whole transaction however deeply nested the call is, so
        -- when handle_new_user assigns the global `user` role and then calls this,
        -- both rows land with an IDENTICAL assigned_at.
        --
        -- That matters because get_user_roles_for_hook aggregates ORDER BY
        -- assigned_at and the access-token hook takes element 1 as primary_role,
        -- which the app surfaces as the user's role. PostgreSQL sorts are not
        -- stable, so a tie makes that value plan-dependent: a fresh signup could
        -- come out with a primary_role of <slug>_user instead of `user`.
        --
        -- Not an escalation - permissions come from the full array - but a
        -- nondeterministic wrong value in a displayed claim is the worst way for
        -- one to be wrong. clock_timestamp() advances within the transaction, so
        -- the organisation role always sorts after the role that preceded it.
        VALUES (p_user_id, v_role_id, NULL, clock_timestamp())
        ON CONFLICT (user_id, role_id) DO NOTHING;
    END IF;
END;
$$;

ALTER FUNCTION "public"."assign_org_member_role_internal"("uuid", "uuid") OWNER TO "postgres";

-- The 20260801030000 header on this function still says auto-assign is false for
-- the boss organisation, which 20260806000000 changed. Migrations are immutable,
-- so the correction goes here.
COMMENT ON FUNCTION "public"."assign_org_member_role_internal"("uuid", "uuid") IS
    'Assigns an organisation''s user-kind role when organisations.auto_assign_member_role is set. Shared by join, admin approval, invite redemption and (since 20260806000000) the signup trigger for the boss organisation. Uses clock_timestamp() so the row cannot tie with a role assigned earlier in the same transaction - get_user_roles_for_hook orders by assigned_at and the token hook takes the first element as primary_role.';
