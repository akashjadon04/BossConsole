-- ============================================================================
-- BOSS Database Schema: Organisation invite links
-- ============================================================================
-- File: 20260801040000_organisation_invites.sql
-- Description:
--   Token-based join links. An organisation admin mints one, shares the URL, and
--   the recipient redeems it from inside the desktop app.
--
-- Token handling mirrors plugin_api_keys (20260204000000): the plaintext exists
-- exactly once, in create_organisation_invite's return value, and only the
-- SHA-256 hash is stored. list_organisation_invites projects token_prefix and
-- never token_hash.
--
-- WHY REDEMPTION IS authenticated-ONLY, and why that matters:
--   redeem_organisation_invite requires auth.uid(), so the DESKTOP APP is what
--   redeems. The web landing page served by the `organisation` edge function only
--   previews the org name and bounces into boss://organisation/join?token=...
--   That is not an inconvenience -- it is the property that makes an email
--   scanner's link prefetch (Outlook SafeLinks and friends) unable to consume
--   somebody's invite.
--
-- Dependencies:
--   - 20260801000000_organisation_tables.sql
--   - 20260801010000_organisation_permissions_and_guards.sql
--   - 20260801030000_organisation_core_rpcs.sql (assign_org_member_role_internal)
--
-- Next migration: 20260801050000_organisation_handoff_tokens.sql
-- ============================================================================


-- ============================================================================
-- SECTION 1: create_organisation_invite
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."create_organisation_invite"(
    "p_org_id" "uuid",
    "p_role_id" "uuid" DEFAULT NULL::"uuid",
    "p_label" "text" DEFAULT NULL::"text",
    "p_max_uses" integer DEFAULT NULL::integer,
    "p_expires_in_hours" integer DEFAULT 168,
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_kind TEXT;
    v_token TEXT;
    v_hash TEXT;
    v_prefix TEXT;
    v_invite_id UUID;
    v_expires_at TIMESTAMPTZ;
BEGIN
    -- p_actor_id is honoured only for a service_role caller (the organisation
    -- edge function's admin page). See resolve_org_actor in 20260801010000.
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Permission denied');
    END IF;

    -- 1 hour to 30 days. There is no unbounded option: a link that never expires
    -- is a permanent organisation backdoor, and organisation_invites.expires_at
    -- is NOT NULL for the same reason.
    IF p_expires_in_hours IS NULL OR p_expires_in_hours < 1 OR p_expires_in_hours > 720 THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Expiry must be between 1 and 720 hours (30 days)');
    END IF;

    IF p_max_uses IS NOT NULL AND p_max_uses < 1 THEN
        RETURN jsonb_build_object('success', false, 'error', 'max_uses must be at least 1');
    END IF;

    IF p_role_id IS NOT NULL THEN
        SELECT orl.kind INTO v_kind
        FROM public.organisation_roles orl
        WHERE orl.org_id = p_org_id AND orl.role_id = p_role_id;

        IF NOT FOUND THEN
            RETURN jsonb_build_object('success', false, 'error',
                'That role does not belong to this organisation');
        END IF;

        -- An admin-granting link is an organisation-takeover primitive the moment
        -- the URL leaks -- into a chat log, a screenshot, a mail archive. Admin
        -- promotion must be a deliberate, per-person act via
        -- assign_organisation_role.
        IF v_kind = 'admin' THEN
            RETURN jsonb_build_object('success', false, 'error',
                'An invite link cannot grant the administrator role -- assign it explicitly after they join');
        END IF;
    END IF;

    -- 32 random bytes ~ 256 bits. translate() maps '+' and '/' to URL-safe
    -- characters and, because the FROM set is 3 characters against a 2-character
    -- TO set, DELETES the '=' padding.
    v_token  := 'boss_inv_' || translate(
        pg_catalog.encode(extensions.gen_random_bytes(32), 'base64'), '+/=', '-_');
    v_hash   := pg_catalog.encode(extensions.digest(v_token, 'sha256'), 'hex');
    v_prefix := left(v_token, 17);
    v_expires_at := now() + make_interval(hours => p_expires_in_hours);

    INSERT INTO public.organisation_invites (
        org_id, token_hash, token_prefix, role_id, label, max_uses, expires_at, created_by
    ) VALUES (
        p_org_id, v_hash, v_prefix, p_role_id, p_label, p_max_uses, v_expires_at, v_actor
    ) RETURNING id INTO v_invite_id;

    -- The ONLY time the plaintext token exists. Never log it. The client builds
    -- <SUPABASE_FUNCTION_URL>/organisation/join/<token>.
    RETURN jsonb_build_object(
        'success', true,
        'invite_id', v_invite_id::text,
        'token', v_token,
        'token_prefix', v_prefix,
        'expires_at', v_expires_at,
        'max_uses', p_max_uses);
END;
$$;

ALTER FUNCTION "public"."create_organisation_invite"("uuid", "uuid", "text", integer, integer, "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."create_organisation_invite"("uuid", "uuid", "text", integer, integer, "uuid") IS 'Mints an organisation invite link. Returns the plaintext token exactly once -- only its SHA-256 hash is stored. Refuses an admin-kind role (a leaked admin link is an org-takeover primitive) and caps expiry at 30 days.';


-- ============================================================================
-- SECTION 2: redeem_organisation_invite
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."redeem_organisation_invite"("p_token" "text")
RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_user_id UUID;
    v_hash TEXT;
    v_inv public.organisation_invites;
    v_slug TEXT;
    v_name TEXT;
BEGIN
    v_user_id := auth.uid();
    IF v_user_id IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    IF p_token IS NULL OR btrim(p_token) = '' THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite link is invalid or expired');
    END IF;

    v_hash := pg_catalog.encode(extensions.digest(btrim(p_token), 'sha256'), 'hex');

    -- FOR UPDATE is what makes max_uses race-free: two simultaneous redemptions
    -- of a single-use link serialize, and the second sees uses >= max_uses.
    SELECT * INTO v_inv
    FROM public.organisation_invites i
    WHERE i.token_hash = v_hash
    FOR UPDATE;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite link is invalid or expired');
    END IF;

    SELECT o.slug, o.name INTO v_slug, v_name
    FROM public.organisations o WHERE o.id = v_inv.org_id;

    -- IDEMPOTENCY IS CHECKED FIRST, BEFORE revoked/expired/exhausted, AND THE
    -- ORDER MATTERS.
    --
    -- The obvious order -- validate the invite, then dedupe -- is wrong for the
    -- common case. A single-use link (max_uses = 1) is exhausted the instant its
    -- one redeemer uses it, so when that same person clicks their own link again
    -- (a double-click, a re-opened tab, a mail client prefetch followed by a real
    -- click) a validity-first check answers "invalid or expired" -- about a link
    -- that worked perfectly and made them a member. A test caught exactly this.
    --
    -- Answering already_member here grants nothing: the row proves they redeemed
    -- it before, and no state changes. It is deliberately returned even for a
    -- revoked or expired invite, because revoking a link is not a mechanism for
    -- ejecting members who already used it -- remove_organisation_member is.
    --
    -- AND still a member. The redemption row outlives the membership -- there is
    -- no 'removed' status, remove_organisation_member deletes the row, and the
    -- redemption's foreign keys point at the invite and the user, not at the
    -- membership. Keying only on the redemption meant that someone removed from
    -- the organisation, or who left, clicking a still-live link they had used
    -- before was told "already a member" and NOT re-added: a dead end they could
    -- never escape through that link. Falling through re-admits them via the
    -- ON CONFLICT DO UPDATE below, and the redemptions insert's ON CONFLICT DO
    -- NOTHING keeps uses from double-counting.
    IF EXISTS (
        SELECT 1 FROM public.organisation_invite_redemptions red
        WHERE red.invite_id = v_inv.id AND red.user_id = v_user_id
    ) AND EXISTS (
        SELECT 1 FROM public.organisation_members m
        WHERE m.org_id = v_inv.org_id AND m.user_id = v_user_id AND m.status = 'active'
    ) THEN
        RETURN jsonb_build_object('success', true, 'already_member', true,
            'org_id', v_inv.org_id::text, 'slug', v_slug, 'name', v_name);
    END IF;

    -- Revoked / expired / exhausted all return the SAME message as "not found".
    -- Any distinction would make this an invite-token enumeration oracle --
    -- "revoked" confirms a token existed.
    IF v_inv.revoked_at IS NOT NULL
       OR v_inv.expires_at <= now()
       OR (v_inv.max_uses IS NOT NULL AND v_inv.uses >= v_inv.max_uses) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite link is invalid or expired');
    END IF;

    INSERT INTO public.organisation_members (
        org_id, user_id, status, joined_at, join_source, invited_by, invited_at
    ) VALUES (
        v_inv.org_id, v_user_id, 'active', now(), 'invite', v_inv.created_by, v_inv.created_at
    )
    ON CONFLICT (org_id, user_id) DO UPDATE
        SET status = 'active',
            joined_at = COALESCE(organisation_members.joined_at, now()),
            updated_at = now();

    IF v_inv.role_id IS NOT NULL THEN
        INSERT INTO public.user_roles (user_id, role_id, assigned_by, assigned_at)
        VALUES (v_user_id, v_inv.role_id, v_inv.created_by, now())
        ON CONFLICT (user_id, role_id) DO NOTHING;
    ELSE
        PERFORM public.assign_org_member_role_internal(v_inv.org_id, v_user_id);
    END IF;

    -- The increment is GUARDED on the redemption actually inserting.
    --
    -- The comment above claimed ON CONFLICT DO NOTHING kept uses from double-counting, but the
    -- UPDATE was unconditional, so it did not. A member who is removed and re-clicks a still-live
    -- link now takes the fall-through path deliberately, which is exactly the case that produced
    -- one redemption row and two increments - burning a use of a capped link on somebody who had
    -- already consumed one.
    INSERT INTO public.organisation_invite_redemptions (invite_id, user_id)
    VALUES (v_inv.id, v_user_id)
    ON CONFLICT (invite_id, user_id) DO NOTHING;

    IF FOUND THEN
        UPDATE public.organisation_invites SET uses = uses + 1 WHERE id = v_inv.id;
    END IF;

    RETURN jsonb_build_object('success', true, 'org_id', v_inv.org_id::text,
        'slug', v_slug, 'name', v_name, 'status', 'active');
END;
$$;

ALTER FUNCTION "public"."redeem_organisation_invite"("text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."redeem_organisation_invite"("text") IS 'Redeems an invite link for the CURRENT user. authenticated-only, so the desktop app is what redeems -- which is why an email scanner prefetching the invite URL cannot consume it. All four failure modes return one identical message so this is not a token oracle.';


-- ============================================================================
-- SECTION 3: List and revoke
-- ============================================================================

CREATE OR REPLACE FUNCTION "public"."list_organisation_invites"(
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

    IF NOT public.user_is_org_admin(v_actor, p_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
    END IF;

    -- token_hash is deliberately absent from this projection. A client that could
    -- read the hash could confirm a guessed token offline, so the invite table is
    -- never selected directly by the desktop app either (no authenticated table
    -- grant -- see 20260801000000).
    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT i.id AS invite_id, i.token_prefix, i.label,
               i.role_id, r.name AS role_name,
               i.max_uses, i.uses, i.expires_at, i.revoked_at, i.created_at,
               cu.email AS created_by_email,
               (i.revoked_at IS NULL
                AND i.expires_at > now()
                AND (i.max_uses IS NULL OR i.uses < i.max_uses)) AS is_live
        FROM public.organisation_invites i
        LEFT JOIN public.roles r ON r.id = i.role_id
        LEFT JOIN auth.users cu ON cu.id = i.created_by
        WHERE i.org_id = p_org_id
        ORDER BY i.created_at DESC
      ) t;

    RETURN jsonb_build_object('success', true, 'data', v_rows);
END;
$$;

ALTER FUNCTION "public"."list_organisation_invites"("uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."list_organisation_invites"("uuid", "uuid") IS 'Invite links for an organisation, masked: token_prefix only, never token_hash. Organisation admins only.';


CREATE OR REPLACE FUNCTION "public"."revoke_organisation_invite"(
    "p_invite_id" "uuid",
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_org_id UUID;
    v_revoked_at TIMESTAMPTZ;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Resolve the organisation from the ROW and authorize against THAT, never
    -- against a caller-supplied org id -- otherwise an admin of organisation A
    -- could revoke organisation B's invites by guessing an id.
    SELECT i.org_id, i.revoked_at INTO v_org_id, v_revoked_at
    FROM public.organisation_invites i WHERE i.id = p_invite_id;

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite not found');
    END IF;

    IF NOT public.user_is_org_admin(v_actor, v_org_id) THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invite not found');
    END IF;

    IF v_revoked_at IS NOT NULL THEN
        RETURN jsonb_build_object('success', true, 'already_revoked', true);
    END IF;

    UPDATE public.organisation_invites
       SET revoked_at = now(), revoked_by = v_actor
     WHERE id = p_invite_id;

    RETURN jsonb_build_object('success', true);
END;
$$;

ALTER FUNCTION "public"."revoke_organisation_invite"("uuid", "uuid") OWNER TO "postgres";


-- get_organisation_invite_preview: display-only, for the web landing page.
-- service_role ONLY, and it NEVER redeems -- the page is unauthenticated and an
-- email scanner may fetch it. Returns the same shape for unknown, expired,
-- revoked and exhausted tokens (valid = false, no organisation name), so the
-- endpoint built on it is not an invite oracle either.
CREATE OR REPLACE FUNCTION "public"."get_organisation_invite_preview"("p_token" "text")
RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_hash TEXT;
    v_org_name TEXT;
    v_org_slug TEXT;
    v_org_description TEXT;
BEGIN
    IF p_token IS NULL OR btrim(p_token) = '' THEN
        RETURN jsonb_build_object('success', true, 'valid', false);
    END IF;

    v_hash := pg_catalog.encode(extensions.digest(btrim(p_token), 'sha256'), 'hex');

    SELECT o.name, o.slug, o.description
      INTO v_org_name, v_org_slug, v_org_description
      FROM public.organisation_invites i
      JOIN public.organisations o ON o.id = i.org_id
     WHERE i.token_hash = v_hash
       AND i.revoked_at IS NULL
       AND i.expires_at > now()
       AND (i.max_uses IS NULL OR i.uses < i.max_uses);

    IF NOT FOUND THEN
        RETURN jsonb_build_object('success', true, 'valid', false);
    END IF;

    RETURN jsonb_build_object('success', true, 'valid', true,
        'name', v_org_name, 'slug', v_org_slug, 'description', v_org_description);
END;
$$;

ALTER FUNCTION "public"."get_organisation_invite_preview"("text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_organisation_invite_preview"("text") IS 'Display-only preview of an invite for the unauthenticated web landing page. NEVER redeems and never consumes a use, so a link prefetch is harmless. Returns { valid: false } identically for unknown, expired, revoked and exhausted tokens.';


-- REVOKE first: 20251023000014_grants.sql sets ALTER DEFAULT PRIVILEGES ... GRANT ALL ON
-- FUNCTIONS TO anon, so every function here is anon-executable the moment it is created
-- and a bare GRANT to authenticated does not take that away. Same trap as ON TABLES,
-- caught there and missed here. None of these were an authorization hole - they all
-- resolve through resolve_org_actor, which returns NULL for anon - but an
-- unauthenticated DB-reachable surface nobody intended is worth closing.
REVOKE EXECUTE ON FUNCTION "public"."create_organisation_invite"("uuid", "uuid", "text", integer, integer, "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."create_organisation_invite"("uuid", "uuid", "text", integer, integer, "uuid") TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."list_organisation_invites"("uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."list_organisation_invites"("uuid", "uuid")                                     TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."revoke_organisation_invite"("uuid", "uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."revoke_organisation_invite"("uuid", "uuid")                                    TO "authenticated", "service_role";

-- redeem_organisation_invite takes NO p_actor_id, deliberately. Redemption must
-- be an act of a signed-in user in the desktop app: that is exactly what makes an
-- email scanner's prefetch of an invite URL unable to consume the invite, and it
-- keeps "joining an organisation" -- which changes the caller's own effective
-- permissions -- off every service-role code path.
REVOKE EXECUTE ON FUNCTION "public"."redeem_organisation_invite"("text") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."redeem_organisation_invite"("text")                                            TO "authenticated";
REVOKE EXECUTE ON FUNCTION "public"."redeem_organisation_invite"("text")                                           FROM PUBLIC, "anon";

REVOKE EXECUTE ON FUNCTION "public"."get_organisation_invite_preview"("text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_organisation_invite_preview"("text") TO "service_role";


-- ============================================================================
-- End of File: 20260801040000_organisation_invites.sql
-- ============================================================================
-- Next Migration: 20260801050000_organisation_handoff_tokens.sql
-- ============================================================================
