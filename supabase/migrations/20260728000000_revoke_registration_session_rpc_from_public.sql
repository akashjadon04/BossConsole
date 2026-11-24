-- Close the anon-reachable path to create_mobile_registration_session that
-- 20260727000000 missed.
--
-- That migration revoked EXECUTE from "anon" and "authenticated", which is not
-- sufficient: PostgreSQL grants EXECUTE on a function to PUBLIC by default when
-- the function is created, and both roles inherit it from there. Verified
-- against production after 20260727000000 was applied — an anon-key POST to
-- /rest/v1/rpc/create_mobile_registration_session still reached the function
-- body and returned its own "User not found or email not confirmed" error,
-- proving execution rather than denial.
--
-- The function is SECURITY DEFINER and mints a registration challenge bound to
-- any account it can find by email, with a caller-supplied challenge value, so
-- anon reachability is an account-takeover path: /register/complete derives the
-- enrolling user from the challenge row.
--
-- service_role keeps its explicit grant from 20251023000014 and is unaffected
-- by a PUBLIC revoke. The function has no callers in the repo; the shipped flow
-- uses the passkey Edge Function's POST /register/challenge, which requires a
-- verified session.

REVOKE ALL ON FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") FROM PUBLIC;

-- Re-assert the intended grant so the end state is explicit rather than implied.
GRANT EXECUTE ON FUNCTION "public"."create_mobile_registration_session"("p_user_email" "text", "p_challenge" "text", "p_session_id" "text") TO "service_role";
