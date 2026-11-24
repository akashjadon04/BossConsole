-- ============================================================================
-- BOSS Database Schema: bind publishing API keys to their owner's organisation
--
-- File: 20260811000000_bind_api_keys_to_publisher_org.sql
--
-- 20260803000000 added plugin_api_keys.org_id, commented "The organisation this
-- key may publish for", and backfilled EVERY existing row to the boss
-- organisation so nothing broke. routes/api-keys.ts never set it on creation
-- either, so new keys were NULL and validate_plugin_api_key's COALESCE made them
-- look boss-bound too.
--
-- The publish path now reads that value (services/publish-org.ts), so with every
-- key pointing at boss the new attribution resolves to boss for every CI publish.
-- The mechanism works and changes nothing. This is the half that gives it
-- something to say.
--
-- A FUNCTION, NOT A ONE-OFF `DO` BLOCK, for two reasons. A test can call the
-- real thing instead of a copy of it pasted into the test file - the alternative
-- is a test that passes while the migration is wrong. And an operator who fixes
-- somebody's membership afterwards can re-run it rather than hand-writing the
-- UPDATE, which is the situation the NOTICE at the end points at.
--
-- THE RULE IS THE SAME ONE THE PUBLISH PATH DERIVES, deliberately: a key binds to
-- its OWNER'S SINGLE NON-SYSTEM ORGANISATION, and only when that owner may
-- actually publish there. Two rules for one question would drift. Each clause
-- earns its place:
--
--   * SINGLE. With two candidates there is nothing to derive - picking one would
--     bind somebody's CI to an organisation they did not choose, and every plugin
--     it creates from then on would be attributed there.
--   * NON-SYSTEM. Every user is an active member of `boss`, so counting it would
--     make the answer "boss" for everyone and this a no-op with extra steps.
--   * MAY PUBLISH. Membership is not publishing rights. An organisation with
--     publish_policy = 'owner_only' must not have every member's key bound to it,
--     because user_can_publish_org_plugin would then refuse every publish that key
--     attempts - turning a working key into a broken one.
--
-- SCOPE, narrow on purpose: only rows currently pointing at the boss organisation
-- or at nothing. A key an administrator has already bound somewhere is left
-- exactly as it is. This clears the backfill's default; it has no opinion about
-- deliberate choices.
--
-- NO `plugins` ROWS ARE RE-ATTRIBUTED. Existing plugins keep the organisation
-- they were created with. Ownership is resolved once, at creation, and
-- re-deriving it for a plugin that already exists would move it between
-- organisations behind its publisher's back.
-- ============================================================================


CREATE OR REPLACE FUNCTION "public"."bind_api_keys_to_publisher_org"()
RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_boss_id   UUID;
    v_bound     INTEGER := 0;
    v_remaining INTEGER := 0;
    v_row       RECORD;
BEGIN
    SELECT o.id INTO v_boss_id FROM public.organisations o WHERE o.slug = 'boss';

    -- Not an error. A deployment without the boss organisation has nothing to
    -- clear: the backfill that created this situation targets that row, and the
    -- trigger that defaults to it leaves org_id NULL when it is absent. The
    -- IS NULL arm below still applies, so this works either way.
    IF v_boss_id IS NULL THEN
        RAISE NOTICE 'No boss organisation; only NULL-bound keys are candidates.';
    END IF;

    FOR v_row IN
        SELECT k.id AS key_id,
               k.user_id,
               k.name,
               sole.org_id AS target_org_id
          FROM public.plugin_api_keys k
          -- The owner's single non-system organisation, or no row at all when they
          -- have none or several. A LATERAL with LIMIT 2 rather than a correlated
          -- scalar subquery, so "more than one" is DETECTABLE instead of raising
          -- 21000 and aborting the whole migration.
          JOIN LATERAL (
              -- array_agg(...)[1], not min(): there is no min() aggregate for uuid,
              -- and min() fails with 42883 at runtime rather than at parse time -
              -- found by running this, not by reading it. The count guard means the
              -- array has exactly one element whenever it is read.
              SELECT CASE WHEN count(*) = 1 THEN (array_agg(candidate.org_id))[1] END AS org_id
                FROM (
                    SELECT m.org_id
                      FROM public.organisation_members m
                      JOIN public.organisations o ON o.id = m.org_id
                     WHERE m.user_id = k.user_id
                       AND m.status = 'active'
                       AND o.is_system = false
                     LIMIT 2
                ) candidate
          ) sole ON sole.org_id IS NOT NULL
         WHERE (k.org_id IS NULL OR k.org_id = v_boss_id)
           AND public.user_can_publish_org_plugin(k.user_id, sole.org_id)
    LOOP
        UPDATE public.plugin_api_keys
           SET org_id = v_row.target_org_id
         WHERE id = v_row.key_id;

        v_bound := v_bound + 1;

        -- Named individually, because the point is that somebody can read the
        -- deploy output and see which CI keys changed hands. A count alone would
        -- not let anyone check it was right.
        RAISE NOTICE 'Bound API key "%" (owner %) to organisation %',
            v_row.name, v_row.user_id, v_row.target_org_id;
    END LOOP;

    SELECT count(*) INTO v_remaining
      FROM public.plugin_api_keys k
     WHERE k.org_id IS NULL OR (v_boss_id IS NOT NULL AND k.org_id = v_boss_id);

    RETURN jsonb_build_object('success', true, 'bound', v_bound, 'remaining', v_remaining);
END;
$$;

ALTER FUNCTION "public"."bind_api_keys_to_publisher_org"() OWNER TO "postgres";

-- Maintenance, not a client operation: it rewrites who other people's keys publish
-- for. Nothing authenticated should be able to call it.
REVOKE EXECUTE ON FUNCTION "public"."bind_api_keys_to_publisher_org"()
    FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."bind_api_keys_to_publisher_org"() TO "service_role";

COMMENT ON FUNCTION "public"."bind_api_keys_to_publisher_org"() IS
    'Binds each plugin_api_keys row still defaulting to the boss organisation to its owner''s single non-system organisation, when that owner may publish there. Same rule the publish path derives (services/publish-org.ts), kept in one place so the two cannot drift. Idempotent; leaves deliberately-bound keys alone. Re-runnable after fixing a membership.';


-- ---------------------------------------------------------------------------
-- Run it once, now, and say what happened
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    v_result JSONB;
BEGIN
    v_result := public.bind_api_keys_to_publisher_org();

    RAISE NOTICE 'Bound % API key(s) to a publisher organisation. % still resolve to boss.',
        v_result->>'bound', v_result->>'remaining';

    -- Worth saying out loud rather than leaving to be discovered: a key left on
    -- boss is not broken, it just publishes as before. Every reason is legitimate -
    -- owner in no organisation, owner in several, or owner without publishing
    -- rights where they are - and each needs a human decision a migration should
    -- not make.
    IF (v_result->>'remaining')::integer > 0 THEN
        RAISE NOTICE 'Those need an explicit organisation: their owner has none, has several, or cannot publish in the one they have.';
    END IF;
END;
$$;
