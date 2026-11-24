-- ============================================================================
-- BOSS Database Schema: Organisation web handoff tokens
-- ============================================================================
-- File: 20260801050000_organisation_handoff_tokens.sql
-- Description:
--   The desktop -> web session handoff. The Organisation plugin cannot put its
--   Supabase JWT in a URL (it would land in browser history, the Referer header
--   and the embedded browser's cache), so instead:
--
--     1. The plugin calls mint_organisation_handoff_token over its AUTHENTICATED
--        client. The token's subject is always auth.uid().
--     2. It opens <SUPABASE_FUNCTION_URL>/organisation/o/<slug>?t=<token>
--        as a browser tab in the main panel.
--     3. The `organisation` edge function calls
--        consume_organisation_handoff_token with its SERVICE-ROLE client, gets
--        the user identity, sets its own HMAC-signed HttpOnly cookie, and 302s
--        to the same URL without ?t= so the bearer leaves the address bar and
--        the history entry.
--
--   The token is URL-safe, single-use and ~5 minutes long, and only its SHA-256
--   hash is stored -- but it IS a bearer credential for those 5 minutes. The
--   consuming page must never echo it and must never log it, not even truncated.
--
-- Dependencies:
--   - 20260801000000_organisation_tables.sql
--   - 20260801010000_organisation_permissions_and_guards.sql (is_org_member)
--
-- Next migration: 20260801060000_organisation_jwt_claims.sql
-- ============================================================================


-- ============================================================================
-- SECTION 1: mint_organisation_handoff_token
-- ============================================================================
-- THE security property of this function is what it does NOT accept: there is
-- no p_user_id parameter. The subject is auth.uid(), always. A caller cannot
-- mint a token that authenticates anybody else, so even a full compromise of the
-- desktop client's RPC surface cannot produce a handoff for another user.

CREATE OR REPLACE FUNCTION "public"."mint_organisation_handoff_token"(
    "p_org_id" "uuid" DEFAULT NULL::"uuid",
    "p_purpose" "text" DEFAULT 'org_view'::"text",
    "p_ttl_seconds" integer DEFAULT 300
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_visibility TEXT;
    v_slug TEXT;
    v_live_count INTEGER;
    v_token TEXT;
    v_hash TEXT;
    v_expires_at TIMESTAMPTZ;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF p_purpose IS NULL OR NOT (p_purpose ~ '^[a-z][a-z0-9_]{1,30}$') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invalid purpose');
    END IF;

    -- 30s..15min. Short enough that a leaked URL is stale almost immediately;
    -- long enough to survive a slow embedded-browser cold start.
    IF p_ttl_seconds IS NULL OR p_ttl_seconds < 30 OR p_ttl_seconds > 900 THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Token lifetime must be between 30 and 900 seconds');
    END IF;

    IF p_org_id IS NOT NULL THEN
        SELECT o.visibility, o.slug INTO v_visibility, v_slug
        FROM public.organisations o WHERE o.id = p_org_id;

        IF NOT FOUND THEN
            RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
        END IF;

        -- MEMBERS ONLY.
        --
        -- This used to admit a non-member for a PUBLIC organisation, with the
        -- rationale that "browse a public org, then request to join" would
        -- otherwise be impossible. That flow does not exist: the page such a
        -- token opens refuses them twice - routes/org-page.ts on the live
        -- is_org_member probe, and get_organisation_detail, which is
        -- member-gated. So the arm bought nothing and the comment described a
        -- feature the next reader would assume worked.
        --
        -- If a visitor view with a "Request to join" action is ever built, widen
        -- this again THEN, together with the page that honours it.
        --
        -- Authority for what the page SHOWS is still re-derived per request by
        -- the edge function; this only decides who may open it at all.
        IF NOT public.is_org_member(p_org_id) THEN
            RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
        END IF;
    END IF;

    -- Cheap flood guard. Each token is one page open; ten live at once is
    -- already generous.
    SELECT count(*) INTO v_live_count
    FROM public.organisation_handoff_tokens t
    WHERE t.user_id = v_user_id
      AND t.consumed_at IS NULL
      AND t.expires_at > now();

    IF v_live_count >= 10 THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Too many open organisation pages -- close some and try again');
    END IF;

    -- URL-safe by construction: translate() maps '+' and '/' and, with a 3-char
    -- FROM against a 2-char TO, DELETES the '=' padding. No percent-encoding is
    -- needed, so the token survives being copied out of and back into a URL.
    v_token := 'boss_ht_' || translate(
        pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_');
    v_hash  := pg_catalog.encode(extensions.digest(v_token, 'sha256'), 'hex');
    v_expires_at := now() + make_interval(secs => p_ttl_seconds);

    INSERT INTO public.organisation_handoff_tokens (token_hash, user_id, org_id, purpose, expires_at)
    VALUES (v_hash, v_user_id, p_org_id, p_purpose, v_expires_at);

    RETURN jsonb_build_object(
        'success', true,
        'token', v_token,
        'purpose', p_purpose,
        'slug', v_slug,
        'expires_at', v_expires_at);
END;
$$;

ALTER FUNCTION "public"."mint_organisation_handoff_token"("uuid", "text", integer) OWNER TO "postgres";

COMMENT ON FUNCTION "public"."mint_organisation_handoff_token"("uuid", "text", integer) IS 'Mints a single-use, URL-safe, ~5-minute token that hands the caller''s identity to the organisation edge function''s web pages. There is deliberately NO p_user_id parameter -- the subject is always auth.uid(), which is the whole security property. Members only -- the public arm was removed because the page it opened refused a non-member anyway.';

REVOKE EXECUTE ON FUNCTION "public"."mint_organisation_handoff_token"("uuid", "text", integer) FROM PUBLIC, "anon";
GRANT  EXECUTE ON FUNCTION "public"."mint_organisation_handoff_token"("uuid", "text", integer) TO "authenticated", "service_role";


-- ============================================================================
-- SECTION 2: consume_organisation_handoff_token
-- ============================================================================
-- The single-statement UPDATE ... WHERE consumed_at IS NULL ... RETURNING IS the
-- single-use primitive. There is no read-then-write window, so no advisory lock
-- and no FOR UPDATE are needed, and a replayed token simply matches zero rows.

CREATE OR REPLACE FUNCTION "public"."consume_organisation_handoff_token"("p_token" "text")
RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_org_id UUID;
    v_purpose TEXT;
    v_expires_at TIMESTAMPTZ;
BEGIN
    IF p_token IS NULL OR btrim(p_token) = '' THEN
        RETURN jsonb_build_object('success', false, 'error', 'Token is invalid, expired or already used');
    END IF;

    UPDATE public.organisation_handoff_tokens t
       SET consumed_at = now()
     WHERE t.token_hash = pg_catalog.encode(extensions.digest(btrim(p_token), 'sha256'), 'hex')
       AND t.consumed_at IS NULL
       AND t.expires_at > now()
    RETURNING t.user_id, t.org_id, t.purpose, t.expires_at
         INTO v_user_id, v_org_id, v_purpose, v_expires_at;

    -- One message for unknown, expired and already-consumed. The caller is an
    -- edge function rendering a browser page; distinguishing the cases would tell
    -- an attacker whether a guessed token ever existed.
    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Token is invalid, expired or already used');
    END IF;

    RETURN jsonb_build_object(
        'success', true,
        'user_id', v_user_id::text,
        'org_id', v_org_id::text,
        'purpose', v_purpose,
        'expires_at', v_expires_at,
        'email', (SELECT u.email FROM auth.users u WHERE u.id = v_user_id),
        'org_slug', (SELECT o.slug FROM public.organisations o WHERE o.id = v_org_id));
END;
$$;

ALTER FUNCTION "public"."consume_organisation_handoff_token"("text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."consume_organisation_handoff_token"("text") IS 'Atomically consumes a handoff token and returns the identity behind it. The single UPDATE ... WHERE consumed_at IS NULL ... RETURNING is the single-use primitive -- no read-then-write race. service_role ONLY: an authenticated caller must never be able to burn or probe a token.';

REVOKE EXECUTE ON FUNCTION "public"."consume_organisation_handoff_token"("text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."consume_organisation_handoff_token"("text") TO "service_role";


-- ============================================================================
-- SECTION 3: Explicit cleanup
-- ============================================================================
-- trigger_cleanup_expired_handoff_tokens (20260801000000) already reaps on ~10%
-- of inserts. This is the deterministic version, for an operator or a scheduled
-- job on an instance where minting has gone quiet.

CREATE OR REPLACE FUNCTION "public"."cleanup_expired_organisation_handoff_tokens"()
RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_deleted INTEGER;
BEGIN
    DELETE FROM public.organisation_handoff_tokens
    WHERE expires_at < now() - interval '1 day';

    GET DIAGNOSTICS v_deleted = ROW_COUNT;

    RETURN jsonb_build_object('success', true, 'deleted', v_deleted);
END;
$$;

ALTER FUNCTION "public"."cleanup_expired_organisation_handoff_tokens"() OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."cleanup_expired_organisation_handoff_tokens"() FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."cleanup_expired_organisation_handoff_tokens"() TO "service_role";


-- ============================================================================
-- End of File: 20260801050000_organisation_handoff_tokens.sql
-- ============================================================================
-- Next Migration: 20260801060000_organisation_jwt_claims.sql
-- ============================================================================
