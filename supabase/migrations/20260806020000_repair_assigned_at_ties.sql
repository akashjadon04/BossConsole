-- ============================================================================
-- BOSS Database Schema: repair the assigned_at ties already written
--
-- File: 20260806020000_repair_assigned_at_ties.sql
--
-- 20260806010000 changed assign_org_member_role_internal to clock_timestamp() so
-- a new signup can no longer produce two user_roles rows with the same
-- assigned_at. It did NOT repair rows already written, and its header claimed
-- "Existing users are unaffected: their `user` row predates the backfill by a
-- real interval, so there is no tie to break."
--
-- THAT CLAIM WAS TRUE OF THE WRONG POPULATION. It holds for users who existed
-- before 20260806000000. It does not hold for anyone created BETWEEN the two
-- deployments, which is exactly the population 20260806010000 was written for:
-- their `user` row and their organisation role row were written by one
-- transaction, so both carry transaction_timestamp() and tie permanently.
--
-- For those users get_user_roles_for_hook's ARRAY_AGG(... ORDER BY assigned_at)
-- has no defined winner, so the primary_role claim stays plan-dependent for the
-- life of the row: a re-plan after ANALYZE can flip a displayed role between
-- `user` and <slug>_user with no data change at all. Fixing the function without
-- fixing the rows leaves that in place forever.
--
-- Idempotent, and correct whether the affected population is zero or every user
-- created in that window - it repairs whatever it finds and is a no-op otherwise,
-- which is why it does not need the count known in advance.
--
-- ONE CASE IT DOES NOT RESOLVE, stated so the claim above is not read too widely:
-- two ORGANISATION user-roles assigned in the same transaction would both shift by
-- the same microsecond and stay tied to each other. They would no longer tie with
-- `user`, so primary_role is still correct, which is what this exists to protect.
-- Not reachable today - only the boss organisation assigns a role at signup.
-- ============================================================================


-- Nudge the ORGANISATION role forward, never the role it ties with. The global
-- `user` role is assigned first by handle_new_user and is what primary_role is
-- supposed to resolve to, so moving the organisation row later restores the
-- intended order rather than picking an arbitrary winner.
--
-- One microsecond is enough: timestamptz resolution is microseconds, so this is
-- the smallest change that makes the sort total, and it cannot reorder the row
-- against anything assigned a measurable interval later.
UPDATE "public"."user_roles" ur
   SET "assigned_at" = ur."assigned_at" + interval '1 microsecond'
  FROM "public"."organisation_roles" orl
 WHERE orl."role_id" = ur."role_id"
   AND orl."kind" = 'user'
   AND EXISTS (
       SELECT 1
         FROM "public"."user_roles" peer
        WHERE peer."user_id" = ur."user_id"
          AND peer."role_id" <> ur."role_id"
          AND peer."assigned_at" = ur."assigned_at"
   );


-- ---------------------------------------------------------------------------
-- A one-off report, not a change: who can now reach a secret through the
-- organisation's user-kind role.
-- ---------------------------------------------------------------------------
--
-- 20260806000000 recorded only the JWT-size cost. There is a second consequence
-- it did not: before it, <slug>_user was held by NOBODY, so anything keyed on
-- that role_id was inert. can_access_secret matches
-- secret_shares.shared_with_role_id against the caller's user_roles, so any
-- pre-existing share targeting that role went from reaching zero people to
-- reaching every active member the moment the backfill ran.
--
-- No such share is expected - the role was days old and held by nobody, so there
-- was no reason to share to it - but "no reason to expect it" is not a check.
-- This RAISEs rather than changes anything: revoking a share the operator made
-- deliberately would be the wrong call to make inside a migration, and a warning
-- in the deploy output puts the answer in front of the person who can decide.
DO $$
DECLARE
    v_count integer;
BEGIN
    SELECT count(*) INTO v_count
      FROM public.secret_shares ss
      JOIN public.organisation_roles orl ON orl.role_id = ss.shared_with_role_id
     WHERE orl.kind = 'user';

    IF v_count > 0 THEN
        RAISE WARNING
            'REVIEW REQUIRED: % secret_shares row(s) target an organisation user-kind role. Those secrets are now reachable by every active member of that organisation, where before 20260806000000 the role was held by nobody.',
            v_count;
    ELSE
        RAISE NOTICE 'No secret_shares rows target an organisation user-kind role.';
    END IF;
END;
$$;
