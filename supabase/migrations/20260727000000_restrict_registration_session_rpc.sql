-- ============================================================================
-- BOSS Database Schema: restrict the cross-device registration RPC
-- ============================================================================
-- File: 20260727000000_restrict_registration_session_rpc.sql
-- Description: Removes the `anon` and `authenticated` EXECUTE grants on
--              `create_mobile_registration_session`, leaving `service_role`.
-- Dependencies:
--   - 20251023000003_passkey_functions.sql (function definition)
--   - 20251023000014_grants.sql (the grants being revoked)
-- ============================================================================
--
-- Why
-- ---
-- `create_mobile_registration_session(p_user_email, p_challenge, p_session_id)`
-- is SECURITY DEFINER. It looks a user up *by email* and inserts a
-- `registration` challenge row bound to that user, with a challenge value the
-- caller supplies. Granted to `anon`, it is a second way to mint a registration
-- challenge for an account the caller does not own — reachable through PostgREST
-- with nothing but the project's anon key, and therefore not subject to any
-- check the Edge Function performs.
--
-- The passkey function now requires a verified session on /register/challenge
-- and enrols the credential against the user recorded on the challenge row.
-- That is only meaningful if the challenge row itself cannot be forged, so this
-- grant has to go with it: otherwise the RPC re-opens exactly the path the
-- function closed.
--
-- Impact
-- ------
-- None expected: there are no callers. The function is referenced only by the
-- 2025-10-23 migration comments describing an earlier cross-device design; the
-- shipped flow calls the Edge Function's /register/challenge instead (grep for
-- `create_mobile_registration_session` across composeApp/ and
-- supabase/functions/ returns migrations only). `service_role` retains EXECUTE,
-- so an Edge Function could still call it deliberately in future.
--
-- The function itself is left in place rather than dropped: revoking is
-- reversible in one statement and does not lose the definition, and dropping a
-- SECURITY DEFINER helper that something outside this repo might reference is a
-- bigger step than this change needs.
--
-- ============================================================================

REVOKE ALL ON FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") FROM "anon";

REVOKE ALL ON FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") FROM "authenticated";

COMMENT ON FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") IS
    'Creates a cross-device WebAuthn registration challenge for a confirmed email. service_role only: this mints a challenge bound to a user without proving the caller owns that account, so it must never be reachable with the anon key. The shipped flow uses the passkey Edge Function POST /register/challenge, which requires a verified session.';


-- ============================================================================
-- End of File: restrict_registration_session_rpc.sql
-- ============================================================================
