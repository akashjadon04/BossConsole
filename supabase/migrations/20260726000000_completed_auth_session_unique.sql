-- ============================================================================
-- BOSS Database Schema: one completed authentication per session
-- ============================================================================
-- File: 20260726000000_completed_auth_session_unique.sql
-- Description: Makes `completed_authentications.session_id` unique so a session
--              cannot end up with two rows. `session_id` is client-supplied, and
--              a duplicate breaks cross-device polling two ways: the `.single()`
--              lookup in /auth/status returns PGRST116 (so the session reports
--              "expired" forever), and a token write scoped by session_id fans
--              out across every matching row instead of exactly one.
-- Dependencies:
--   - 20251023000009_passkey_tables.sql (completed_authentications)
-- Tables changed: public.completed_authentications (1 unique index)
-- ============================================================================
--
-- Apply this BEFORE deploying the `passkey` Edge Function. The function upserts
-- with ON CONFLICT (session_id), which Postgres rejects with 42P10 unless this
-- index exists. The function does carry a fallback (it retries as a plain insert
-- and logs loudly), so a mis-ordered deploy degrades rather than failing every
-- cross-device authentication — but the fallback cannot prevent duplicate rows
-- under concurrency, which is the thing this index exists to make impossible.
--
-- ============================================================================


-- Step 1: retire pre-existing duplicates
-- -----------------------------------------------------------------------------
-- Keeps the newest row per session_id — that is the one a poller would want, and
-- the older ones are by definition superseded ceremonies. Rows are short-lived
-- (5 minute window, probabilistic cleanup trigger), so in practice this deletes
-- nothing; it exists so the index creation below cannot fail on live data.
DELETE FROM "public"."completed_authentications" AS "older"
 WHERE "older"."session_id" IS NOT NULL
   AND EXISTS (
     SELECT 1
       FROM "public"."completed_authentications" AS "newer"
      WHERE "newer"."session_id" = "older"."session_id"
        AND (
          "newer"."created_at" > "older"."created_at"
          OR ("newer"."created_at" = "older"."created_at" AND "newer"."id" > "older"."id")
        )
   );


-- Step 2: enforce uniqueness
-- -----------------------------------------------------------------------------
-- Partial index: session_id is nullable (the direct, non-QR flow stores no
-- completed-authentication row at all today, but the column allows NULL and
-- several NULLs must stay legal).
CREATE UNIQUE INDEX IF NOT EXISTS "idx_completed_auth_session_id_unique"
    ON "public"."completed_authentications" ("session_id")
 WHERE ("session_id" IS NOT NULL);

COMMENT ON INDEX "public"."idx_completed_auth_session_id_unique" IS
    'One completed authentication per cross-device session; the Edge Function upserts on this key.';


-- ============================================================================
-- End of File: completed_auth_session_unique.sql
-- ============================================================================
