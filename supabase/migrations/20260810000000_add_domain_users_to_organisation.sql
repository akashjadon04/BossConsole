-- ============================================================================
-- BOSS Database Schema: adopt the existing users of a verified domain
--
-- File: 20260810000000_add_domain_users_to_organisation.sql
--
-- Until now a verified domain only ever let a user CHOOSE to join: it makes
-- organisation_available_action return 'join' instead of 'request' on a
-- request_to_join organisation, and nothing at all on an invite_only one. The
-- organisation could never reach out; every membership began with the member.
--
-- This inverts that for the one case where the inversion is justified: an
-- administrator of an organisation that has PROVED CONTROL OF THE DNS ZONE
-- adopting the accounts already using addresses at that domain. The proof is the
-- same one the self-service path already trusts, and the actor is an admin of the
-- organisation the users are being added to.
--
-- WHAT THIS GRANTS THE ADDED USERS, stated plainly because it is the part that is
-- easy to miss: membership is `active`, and assign_org_member_role_internal then
-- gives each of them the <slug>_user role IF the organisation has
-- auto_assign_member_role set. Any secret_shares row targeting that role becomes
-- readable by every one of them. That is the same consequence 20260806020000
-- raised a WARNING about for the boss organisation's backfill, and it is why the
-- count is reported back rather than the operation being silent.
--
-- WHAT IT DOES NOT DO:
--   * It does not touch anyone who already has an organisation_members row for
--     this organisation, in ANY status. A pending request stays pending, an
--     invited user stays invited, and somebody previously removed is not
--     silently re-added.
--   * It does not reach unconfirmed accounts. email_confirmed_at IS NOT NULL is
--     the same bar organisation_available_action uses; without it, registering an
--     address at a domain you do not control would be enough to be adopted by it.
--   * It does not reach a reserved domain. gmail.com and friends are in
--     reserved_email_domains precisely so that no organisation can claim the
--     population behind them, and add_organisation_domain already refuses to
--     claim one - this is the backstop for a row that predates that rule.
--   * It is a ONE-OFF over accounts that exist now. Future signups are unchanged:
--     they still join through the self-service path.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- How many accounts would be adopted
-- ---------------------------------------------------------------------------
--
-- Split out from the write so the administrator sees a number before pressing
-- anything, and so the button can hide itself when there is nobody to add. It is
-- the SAME predicate as the write below; keeping them textually adjacent is the
-- only thing stopping the count and the effect drifting apart.
--
-- STABLE, not IMMUTABLE: it reads tables.
CREATE OR REPLACE FUNCTION "public"."count_domain_users_for_organisation"(
    "p_domain_id" "uuid"
) RETURNS integer
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT count(*)::integer
      FROM public.organisation_domains d
      JOIN auth.users u
        ON u.email IS NOT NULL
       AND u.email_confirmed_at IS NOT NULL
       AND lower(split_part(u.email, '@', 2)) = d.domain
     WHERE d.id = p_domain_id
       AND d.verified = true
       AND NOT EXISTS (
           SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = d.domain
       )
       AND NOT EXISTS (
           SELECT 1 FROM public.organisation_members m
            WHERE m.org_id = d.org_id AND m.user_id = u.id
       );
$$;

ALTER FUNCTION "public"."count_domain_users_for_organisation"("uuid") OWNER TO "postgres";

-- No grant to `authenticated`. This is a helper for the RPCs below and for
-- list_organisation_domains, both of which are already admin-gated; exposing it
-- directly would let any authenticated caller count the accounts behind any
-- domain id they could guess.
REVOKE EXECUTE ON FUNCTION "public"."count_domain_users_for_organisation"("uuid")
    FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."count_domain_users_for_organisation"("uuid") TO "service_role";

COMMENT ON FUNCTION "public"."count_domain_users_for_organisation"("uuid") IS
    'How many confirmed accounts at a verified domain are not yet members of its organisation. Same predicate as add_domain_users_to_organisation, kept adjacent so the preview and the effect cannot drift. Not granted to authenticated: callers reach it through admin-gated RPCs.';


-- ---------------------------------------------------------------------------
-- Adopt them
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION "public"."add_domain_users_to_organisation"(
    "p_domain_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor  UUID;
    v_org_id UUID;
    v_domain TEXT;
    v_verified BOOLEAN;
    v_added  INTEGER := 0;
    v_user   RECORD;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- FOR UPDATE on the domain row, so two administrators pressing the button at
    -- the same moment serialise instead of both scanning and both inserting. The
    -- ON CONFLICT below already makes a double insert harmless; this makes the
    -- REPORTED COUNT honest, which the second caller would otherwise overstate.
    SELECT d.org_id, d.domain, d.verified
      INTO v_org_id, v_domain, v_verified
      FROM public.organisation_domains d
     WHERE d.id = p_domain_id
       FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Domain not found');
    END IF;

    -- Authorisation is against the domain's OWN organisation, never one the
    -- caller named. Nothing in the parameter list identifies an organisation, so
    -- there is no org for a caller to substitute.
    IF NOT public.user_is_org_admin(v_actor, v_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    IF NOT v_verified THEN
        RETURN jsonb_build_object('success', false, 'error',
            'The domain must be verified before its users can be added');
    END IF;

    IF EXISTS (SELECT 1 FROM public.reserved_email_domains red WHERE red.domain = v_domain) THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('"%s" is a reserved email domain', v_domain));
    END IF;

    -- A loop rather than one INSERT ... SELECT, because each new member needs
    -- assign_org_member_role_internal run for them and that is a per-row call.
    -- The set is bounded by the accounts at one domain.
    FOR v_user IN
        SELECT u.id
          FROM auth.users u
         WHERE u.email IS NOT NULL
           AND u.email_confirmed_at IS NOT NULL
           AND lower(split_part(u.email, '@', 2)) = v_domain
           AND NOT EXISTS (
               SELECT 1 FROM public.organisation_members m
                WHERE m.org_id = v_org_id AND m.user_id = u.id
           )
    LOOP
        INSERT INTO public.organisation_members (org_id, user_id, status, joined_at, join_source)
        VALUES (v_org_id, v_user.id, 'active', now(), 'domain')
        ON CONFLICT (org_id, user_id) DO NOTHING;

        -- GUARD ON WHAT WAS ACTUALLY INSERTED, not on the loop. The NOT EXISTS
        -- above is evaluated when the cursor opens, so a concurrent join between
        -- then and here lands on the ON CONFLICT and inserts nothing - counting
        -- the iteration would report a member this call did not create.
        IF FOUND THEN
            v_added := v_added + 1;
            -- Honours auto_assign_member_role, and uses clock_timestamp() so the
            -- role cannot tie with one assigned earlier in this transaction.
            PERFORM public.assign_org_member_role_internal(v_org_id, v_user.id);
        END IF;
    END LOOP;

    RETURN jsonb_build_object(
        'success', true,
        'added', v_added,
        'domain', v_domain);
END;
$$;

ALTER FUNCTION "public"."add_domain_users_to_organisation"("uuid", "uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."add_domain_users_to_organisation"("uuid", "uuid")
    FROM PUBLIC, "anon";
GRANT  EXECUTE ON FUNCTION "public"."add_domain_users_to_organisation"("uuid", "uuid")
    TO "authenticated", "service_role";

COMMENT ON FUNCTION "public"."add_domain_users_to_organisation"("uuid", "uuid") IS
    'Adds every confirmed account at a VERIFIED domain as an active member of that domain''s organisation, skipping anyone who already has a membership row in any status. Admin-gated against the domain''s own org. Runs assign_org_member_role_internal per new member, so auto_assign_member_role applies - which means secrets shared to <slug>_user become readable by all of them. One-off over existing accounts; future signups still join through the self-service path.';


-- ---------------------------------------------------------------------------
-- Surface the count on the admin page
-- ---------------------------------------------------------------------------
--
-- list_organisation_domains gains `addable_user_count`. Unchanged signature, so
-- this is a plain CREATE OR REPLACE rather than a DROP - see 20260807000000 for
-- what happens when the argument list does change.
--
-- Computed only for a VERIFIED row. The count subquery scans auth.users for a
-- domain match, which is not an indexed expression, and an unverified claim can
-- never be actioned anyway - so an organisation with a long list of unverified
-- claims costs nothing.
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

    -- The projection and the ordering are the 20260801030000 original, verbatim,
    -- with ONE column appended. Two things were dropped by an earlier draft of
    -- this migration and are called out so they are not dropped again:
    -- `d.created_at`, which is projected but unread today, and the
    -- `is_primary DESC` half of the sort, without which the primary domain stops
    -- coming first on the admin page.
    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT d.id AS domain_id, d.domain, d.is_primary, d.verified, d.verified_at,
               'TXT' AS dns_record_type,
               '_boss-verify.' || d.domain AS dns_record_name,
               'boss-org-verification=' || d.verification_token AS dns_record_value,
               d.created_at,
               CASE
                   WHEN d.verified
                   THEN public.count_domain_users_for_organisation(d.id)
                   ELSE 0
               END AS addable_user_count
        FROM public.organisation_domains d
        WHERE d.org_id = p_org_id
        ORDER BY d.is_primary DESC, d.domain
      ) t;

    RETURN jsonb_build_object('success', true, 'data', v_rows);
END;
$$;

ALTER FUNCTION "public"."list_organisation_domains"("uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."list_organisation_domains"("uuid", "uuid") IS
    'Lists an organisation''s claimed domains for an admin, including the DNS record to publish and, for a verified row, how many existing accounts at that domain could still be added (addable_user_count). Admin-only: the rows carry verification_token.';
