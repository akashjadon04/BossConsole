-- ============================================================================
-- BOSS Database Schema: Passkey verification columns
-- ============================================================================
-- File: 20260725000000_passkey_verification_columns.sql
-- Description: Columns required by the server-side WebAuthn verification in the
--              `passkey` Edge Function (issue #35). Without them the function
--              cannot detect cloned authenticators, cannot verify credentials
--              registered with RS256, and cannot pin an assertion to the
--              relying party the credential was registered for.
-- Dependencies:
--   - 20251023000009_passkey_tables.sql (user_passkeys)
-- Tables changed: public.user_passkeys (3 new nullable columns)
-- Standards: WebAuthn Level 2 §6.1 (authenticator data), §7.2 (assertion
--            verification, signature counter rule)
-- ============================================================================
--
-- Deploy this migration together with (or before) the `passkey` Edge Function.
-- The function writes these columns on registration and on every successful
-- assertion; if they do not exist the writes fail.
--
-- Backfill: none required.
--   - public_key_alg is nullable; the function reads NULL as ES256 (-7), which
--     is what every credential registered before this change used
--   - sign_count has DEFAULT 0, so ADD COLUMN backfills existing rows to 0 --
--     "no counter recorded yet", which is exactly how the function reads it. The
--     function also tolerates NULL (a row inserted while the column did not
--     exist, via the degraded path in storePasskeyInDB), but a migrated table
--     will not contain NULL here.
--   - rp_id is nullable; NULL means fall back to the configured RP ID allow-list
--     instead of an exact per-credential match
--
-- ============================================================================


-- Column: user_passkeys.public_key_alg
-- -----------------------------------------------------------------------------
-- COSE algorithm identifier (IANA COSE Algorithms registry) of public_key.
--   -7   ES256: public_key is the raw 65-byte uncompressed EC point
--   -257 RS256: public_key is SPKI DER
-- The verification path needs this to know how to import the stored key; before
-- it existed RS256 was advertised in pubKeyCredParams but only ES256 could be
-- verified, so an authenticator that chose RS256 could register and then never
-- authenticate.
ALTER TABLE "public"."user_passkeys"
    ADD COLUMN IF NOT EXISTS "public_key_alg" integer;

COMMENT ON COLUMN "public"."user_passkeys"."public_key_alg" IS
    'COSE algorithm of public_key (-7 = ES256 raw EC point, -257 = RS256 SPKI DER). NULL means ES256 (pre-existing rows).';


-- Column: user_passkeys.sign_count
-- -----------------------------------------------------------------------------
-- Highest signature counter observed from this authenticator.
--
-- WebAuthn Level 2 §7.2 step 21: if the counter fails to advance the credential
-- may have been cloned. Authenticators that do not implement a counter report 0
-- forever (Apple's platform authenticator, for example), so 0-and-still-0 is
-- accepted rather than treated as a clone; any other non-increase is rejected.
ALTER TABLE "public"."user_passkeys"
    ADD COLUMN IF NOT EXISTS "sign_count" bigint DEFAULT 0;

COMMENT ON COLUMN "public"."user_passkeys"."sign_count" IS
    'Highest WebAuthn signature counter seen for this credential. 0 means the authenticator does not maintain a counter.';


-- Column: user_passkeys.rp_id
-- -----------------------------------------------------------------------------
-- Relying party ID this credential was registered for, resolved by matching
-- attestation authData.rpIdHash against the allowed RP IDs at registration.
--
-- Assertions for credentials that carry an rp_id are pinned to it exactly;
-- legacy rows (NULL) fall back to the allow-list.
ALTER TABLE "public"."user_passkeys"
    ADD COLUMN IF NOT EXISTS "rp_id" "text";

COMMENT ON COLUMN "public"."user_passkeys"."rp_id" IS
    'RP ID (WebAuthn relying party identifier) this credential was registered for; NULL for credentials registered before rpIdHash verification existed.';


-- ============================================================================
-- End of File: passkey_verification_columns.sql
-- ============================================================================
