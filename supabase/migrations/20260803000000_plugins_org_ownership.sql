-- ============================================================================
-- BOSS Database Schema: Plugin ownership by organisation, and visibility
-- ============================================================================
-- File: 20260803000000_plugins_org_ownership.sql
-- Description:
--   Every plugin becomes owned by an organisation, and the owning organisation
--   decides whether it is unlisted, organisation-searchable, or public. Adds the
--   probes the plugin-store edge function needs, since that function runs with
--   the service role and therefore cannot rely on RLS.
--
-- Dependencies:
--   - 20260130000000_plugin_store_tables.sql  (plugins, plugin_versions, RPCs)
--   - 20260630000000_plugin_install_permissions.sql (required_permissions)
--   - 20260204000000_plugin_store_api_keys.sql (plugin_api_keys)
--   - 20260801010000 / 20260801070000          (org predicates, boss org)
--
-- MUST RUN AFTER 20260801070000 (the boss organisation seed) -- the backfills
-- target that organisation.
--
-- ############################################################################
-- ## THE LIVE PLUGIN STORE RUNS ON THESE FUNCTIONS. FOUR RULES.             ##
-- ############################################################################
--
-- (1) NEVER ADD A PARAMETER TO search_plugins OR get_plugin_versions.
--     CREATE OR REPLACE with a new argument list creates a SECOND OVERLOAD, and
--     PostgREST then fails the call with "function is not unique" -- the entire
--     store 500s. Viewer-scoped variants get their OWN names
--     (*_for_viewer) instead, and both they and the originals delegate to a
--     single *_internal body so the two can never drift.
--
-- (2) get_plugin_with_stats MUST BE DROPPED, NOT REPLACED. Its RETURNS TABLE
--     gains columns and CREATE OR REPLACE cannot change a return type. Dropping
--     drops its grants, so they are re-issued below -- to authenticated AND anon,
--     because the store browses anonymously. 20260630000000 hit exactly this and
--     its comment says so.
--
-- (3) visibility MUST DEFAULT TO 'public'. functions/plugin-store/services/
--     plugins.ts::createPlugin does not set it. Any other default would silently
--     unpublish every newly published plugin.
--
-- (4) plugins.org_id STAYS NULLABLE, with a BEFORE INSERT default.
--     createPlugin does not set org_id either. A plain NOT NULL would break every
--     publish; and if the boss-org seed was skipped (an empty database), a
--     SET NOT NULL would fail the deploy outright. Adding NOT NULL is a follow-up
--     migration once the backfill is confirmed in production.
--
-- ROLLOUT: this migration is intentionally a NO-OP for behaviour. Everything is
-- backfilled to public + the boss organisation, so the store looks identical
-- afterwards. The gates only start biting when the edge function is deployed and
-- someone sets a plugin non-public. Deploy in that order; the reverse empties the
-- store for every user.
--
-- Next migration: 20260804000000_organisation_detail_rpc.sql.
-- ============================================================================


-- ============================================================================
-- SECTION 1: Schema and backfill
-- ============================================================================

ALTER TABLE "public"."plugins" ADD COLUMN IF NOT EXISTS "org_id" "uuid";
ALTER TABLE "public"."plugins" ADD COLUMN IF NOT EXISTS "visibility" "text" DEFAULT 'public'::"text" NOT NULL;

ALTER TABLE "public"."plugins" DROP CONSTRAINT IF EXISTS "plugins_visibility_check";
ALTER TABLE "public"."plugins" ADD CONSTRAINT "plugins_visibility_check"
    CHECK ("visibility" = ANY (ARRAY['unlisted'::"text", 'org'::"text", 'public'::"text"]));

ALTER TABLE "public"."plugins" DROP CONSTRAINT IF EXISTS "plugins_org_id_fkey";
ALTER TABLE "public"."plugins" ADD CONSTRAINT "plugins_org_id_fkey"
    FOREIGN KEY ("org_id") REFERENCES "public"."organisations"("id") ON DELETE RESTRICT;

COMMENT ON COLUMN "public"."plugins"."org_id" IS
'Owning organisation. Backfilled to the boss organisation for every pre-existing plugin. Nullable with a BEFORE INSERT default because the plugin-store edge function does not set it; see rule (4) in this migration''s header.';

COMMENT ON COLUMN "public"."plugins"."visibility" IS
'public = listed in the store for everyone (the default and the backfilled value). org = only members of org_id may see or download it. unlisted = only the author and organisation admins. Enforced by user_can_view_plugin_row, and -- because the store''s edge function uses the service role and bypasses RLS -- ALSO by an explicit gate in routes/download.ts.';

-- Backfill. Both are idempotent.
UPDATE "public"."plugins" SET "visibility" = 'public'
 WHERE "visibility" IS NULL OR "visibility" NOT IN ('unlisted', 'org', 'public');

UPDATE "public"."plugins" p SET "org_id" = o."id"
  FROM "public"."organisations" o
 WHERE o."slug" = 'boss' AND p."org_id" IS NULL;

-- Keeps the edge function's org_id-less INSERT working while still guaranteeing
-- ownership. BEFORE INSERT, so it fires ahead of any future NOT NULL check.
CREATE OR REPLACE FUNCTION "public"."plugins_default_org"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    IF NEW.org_id IS NULL THEN
        SELECT o.id INTO NEW.org_id FROM public.organisations o WHERE o.slug = 'boss';
    END IF;
    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."plugins_default_org"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."plugins_default_org"() IS 'Defaults plugins.org_id to the boss organisation. Exists so the plugin-store edge function, which does not set org_id, keeps working unchanged.';

DROP TRIGGER IF EXISTS "plugins_default_org" ON "public"."plugins";
CREATE TRIGGER "plugins_default_org" BEFORE INSERT ON "public"."plugins"
    FOR EACH ROW EXECUTE FUNCTION "public"."plugins_default_org"();

CREATE INDEX IF NOT EXISTS "idx_plugins_org_id" ON "public"."plugins" ("org_id");
CREATE INDEX IF NOT EXISTS "idx_plugins_visibility"
    ON "public"."plugins" ("visibility", "published") WHERE "published";


-- Bind CI publishing keys to an organisation.
--
-- WHY THE BACKFILL IS THE BOSS ORG AND NOT "THE USER'S SINGLE ORGANISATION":
-- every plugin repo under boss_plugins releases through an API key. Resolving a
-- NULL org_id as "the key owner's only organisation" is ambiguous the moment that
-- person joins a second one, and it would break their CI at that point rather
-- than now -- the worst possible time to discover it. The boss organisation is
-- deterministic, matches where the plugins themselves were just backfilled, and
-- keeps every existing release workflow working untouched.
ALTER TABLE "public"."plugin_api_keys" ADD COLUMN IF NOT EXISTS "org_id" "uuid";

ALTER TABLE "public"."plugin_api_keys" DROP CONSTRAINT IF EXISTS "plugin_api_keys_org_id_fkey";
ALTER TABLE "public"."plugin_api_keys" ADD CONSTRAINT "plugin_api_keys_org_id_fkey"
    FOREIGN KEY ("org_id") REFERENCES "public"."organisations"("id") ON DELETE SET NULL;

UPDATE "public"."plugin_api_keys" k SET "org_id" = o."id"
  FROM "public"."organisations" o
 WHERE o."slug" = 'boss' AND k."org_id" IS NULL;

COMMENT ON COLUMN "public"."plugin_api_keys"."org_id" IS
'The organisation this key may publish for. A leaked CI key can therefore publish into exactly one organisation. NULL is treated as the boss organisation by the publish path; existing keys were backfilled to it.';


-- ============================================================================
-- SECTION 2: The visibility predicate
-- ============================================================================
-- One function decides who may see a plugin row, used by RLS, by the store RPCs,
-- and by the edge function's service-role probe. Written over the row's COLUMNS
-- rather than an id so RLS can call it without a self-referencing subquery.

CREATE OR REPLACE FUNCTION "public"."user_can_view_plugin_row"(
    "p_user_id" "uuid",
    "p_visibility" "text",
    "p_org_id" "uuid",
    "p_author_id" "uuid",
    "p_published" boolean
) RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT CASE
        -- Anonymous browsing of the public store keeps working.
        WHEN p_visibility = 'public'  AND p_published THEN true
        WHEN p_user_id IS NULL        THEN false
        WHEN public.is_user_admin(p_user_id) THEN true
        -- An author always sees their own work, including unpublished drafts.
        WHEN p_author_id = p_user_id  THEN true
        WHEN p_visibility = 'org'      THEN p_published AND public.user_is_org_member(p_user_id, p_org_id)
        -- 'unlisted' is installable-by-link, so it is deliberately NOT visible to
        -- ordinary members -- only to the author (above) and organisation admins.
        WHEN p_visibility = 'unlisted' THEN public.user_is_org_admin(p_user_id, p_org_id)
        ELSE false
    END;
$$;

ALTER FUNCTION "public"."user_can_view_plugin_row"("uuid","text","uuid","uuid",boolean) OWNER TO "postgres";

COMMENT ON FUNCTION "public"."user_can_view_plugin_row"("uuid","text","uuid","uuid",boolean) IS 'Whether a user may see a plugin: public+published to anyone (including anonymous), org to active members, unlisted to the author and organisation admins, anything to the author or a global admin. The single visibility rule shared by RLS, the store RPCs and the edge function.';

-- service_role ONLY, and the reason is the whole point of the split below.
--
-- Every argument is caller-supplied and no plugin has to exist, so with a grant to
-- anon/authenticated this function reduces to the three predicates that
-- 20260801010000 SECTION 4 deliberately REVOKED from authenticated:
--
--   (victim, 'unlisted', org, NULL, false) => user_is_org_admin(victim, org)
--   (victim, 'org',      org, NULL, true)  => user_is_org_member(victim, org)
--   (victim, 'nonsense', NULL, NULL, false) => is_user_admin(victim)
--
-- One POST /rest/v1/rpc/user_can_view_plugin_row per question, and it answers about
-- ANY subject. Granting it back would reopen all three, to anon as well.
REVOKE EXECUTE ON FUNCTION "public"."user_can_view_plugin_row"("uuid","text","uuid","uuid",boolean)
    FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."user_can_view_plugin_row"("uuid","text","uuid","uuid",boolean)
    TO "service_role";


-- The self-subject form, for RLS.
--
-- Same rule, but the subject is auth.uid() rather than a parameter, so it can be
-- granted to anon and authenticated without becoming an oracle: a caller can only
-- ever ask about themselves. This is the is_org_member / user_is_org_member split
-- that the rest of this batch already uses, applied to the one helper that had
-- been left as the arbitrary-subject form in four policies.
CREATE OR REPLACE FUNCTION "public"."can_view_plugin_row"(
    "p_visibility" "text",
    "p_org_id" "uuid",
    "p_author_id" "uuid",
    "p_published" boolean
) RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT public.user_can_view_plugin_row(
        auth.uid(), p_visibility, p_org_id, p_author_id, p_published);
$$;

ALTER FUNCTION "public"."can_view_plugin_row"("text","uuid","uuid",boolean) OWNER TO "postgres";

COMMENT ON FUNCTION "public"."can_view_plugin_row"("text","uuid","uuid",boolean) IS
'Whether the CALLER may see a plugin row. The subject is auth.uid(), never a parameter, which is what makes it safe to grant to anon and authenticated -- see the note on user_can_view_plugin_row. Used by the plugins/versions/tags/screenshots SELECT policies.';

-- REVOKE first: 20251023000014_grants.sql sets ALTER DEFAULT PRIVILEGES ... GRANT ALL ON
-- FUNCTIONS TO anon, so every function here is anon-executable the moment it is created
-- and a bare GRANT to authenticated does not take that away. Same trap as ON TABLES,
-- caught there and missed here. None of these were an authorization hole - they all
-- resolve through resolve_org_actor, which returns NULL for anon - but an
-- unauthenticated DB-reachable surface nobody intended is worth closing.
REVOKE EXECUTE ON FUNCTION "public"."can_view_plugin_row"("text","uuid","uuid",boolean) FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."can_view_plugin_row"("text","uuid","uuid",boolean)
    TO "anon", "authenticated", "service_role";


-- The service-role probe. routes/download.ts MUST call this: that route resolves
-- the plugin with the service-role client, which bypasses RLS entirely, so
-- without an explicit check any caller who knows a plugin_id can download an
-- organisation-private JAR. Plugin ids are reverse-DNS and guessable.
--
-- Takes the UUID plugins.id (matching plugin_versions.plugin_id), not the text
-- plugin_id, and accepts NULL for an anonymous viewer.
CREATE OR REPLACE FUNCTION "public"."user_can_view_plugin"("p_user_id" "uuid", "p_plugin_id" "uuid")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.plugins p
        WHERE p.id = p_plugin_id
          AND public.user_can_view_plugin_row(p_user_id, p.visibility, p.org_id, p.author_id, p.published)
    );
$$;

ALTER FUNCTION "public"."user_can_view_plugin"("uuid","uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."user_can_view_plugin"("uuid","uuid") IS 'Service-role probe for the plugin-store edge function, which holds no JWT-scoped session. p_plugin_id is the UUID plugins.id. Returning false must make the route answer 404 with the same body a genuine miss returns, or it becomes an existence oracle for unlisted plugins.';

REVOKE EXECUTE ON FUNCTION "public"."user_can_view_plugin"("uuid","uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."user_can_view_plugin"("uuid","uuid") TO "service_role";


-- ============================================================================
-- SECTION 3: search_plugins -- one body, two entry points
-- ============================================================================
-- The 120-line body lives in search_plugins_internal. search_plugins keeps its
-- exact original signature (so grants and PostgREST resolution are untouched) and
-- passes auth.uid(); search_plugins_for_viewer passes an explicit viewer for the
-- service-role path. Neither can drift from the other.

CREATE OR REPLACE FUNCTION "public"."search_plugins_internal"(
    "p_viewer_id" "uuid",
    "p_query" "text" DEFAULT ''::"text",
    "p_type" "text" DEFAULT NULL::"text",
    "p_tags" "text"[] DEFAULT NULL::"text"[],
    "p_min_rating" numeric DEFAULT 0,
    "p_verified_only" boolean DEFAULT false,
    "p_page" integer DEFAULT 1,
    "p_page_size" integer DEFAULT 20,
    "p_sort_by" "text" DEFAULT 'downloads'::"text"
) RETURNS TABLE("plugins" "jsonb", "total_count" bigint)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_offset INT;
    v_plugins JSONB;
    v_total BIGINT;
BEGIN
    v_offset := (p_page - 1) * p_page_size;

    SELECT COUNT(*)::BIGINT INTO v_total
    FROM public.plugins p
    -- Was: p.published = true.
    WHERE public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
    AND (
        p_query = ''
        OR to_tsvector('english'::regconfig, p.display_name || ' ' || COALESCE(p.description, '')) @@ plainto_tsquery('english'::regconfig, p_query)
        OR p.plugin_id ILIKE '%' || p_query || '%'
    )
    AND (p_type IS NULL OR p.type = p_type)
    AND (p_verified_only = false OR p.verified = true)
    AND (
        p_tags IS NULL
        OR EXISTS (
            SELECT 1 FROM public.plugin_tags pt
            WHERE pt.plugin_id = p.id AND pt.tag = ANY(p_tags)
        )
    )
    AND (
        p_min_rating = 0
        OR COALESCE((SELECT AVG(pr.rating) FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id), 0) >= p_min_rating
    );

    SELECT jsonb_agg(plugin_data ORDER BY sort_key DESC)
    INTO v_plugins
    FROM (
        SELECT
            jsonb_build_object(
                'id', p.id,
                'pluginId', p.plugin_id,
                'displayName', p.display_name,
                'description', p.description,
                'author', p.author_name,
                'type', p.type,
                'apiVersion', p.api_version,
                'verified', p.verified,
                'iconUrl', p.icon_url,
                'url', p.homepage_url,
                'version', (
                    SELECT pv.version FROM public.plugin_versions pv
                    WHERE pv.plugin_id = p.id ORDER BY pv.published_at DESC LIMIT 1
                ),
                'rating', COALESCE(
                    (SELECT AVG(pr.rating)::NUMERIC(3,2) FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id), 0),
                'ratingCount', (SELECT COUNT(*)::INT FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id),
                'downloadCount', (SELECT COUNT(*)::INT FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id),
                'tags', COALESCE(
                    (SELECT ARRAY_AGG(pt.tag) FROM public.plugin_tags pt WHERE pt.plugin_id = p.id), ARRAY[]::TEXT[]),
                'requiredPermissions', COALESCE(p.required_permissions, ARRAY[]::TEXT[]),
                -- New, additive keys. types/schemas.ts marks them optional so an
                -- older desktop client ignores them.
                'orgId', p.org_id,
                'orgSlug', (SELECT o.slug FROM public.organisations o WHERE o.id = p.org_id),
                'visibility', p.visibility,
                'updatedAt', p.updated_at
            ) AS plugin_data,
            CASE p_sort_by
                WHEN 'name' THEN 0
                WHEN 'downloads' THEN (SELECT COUNT(*) FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id)
                WHEN 'rating' THEN COALESCE(
                    (SELECT AVG(pr.rating) * 100 FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id)::BIGINT, 0)
                WHEN 'newest' THEN EXTRACT(EPOCH FROM p.created_at)::BIGINT
                WHEN 'updated' THEN EXTRACT(EPOCH FROM p.updated_at)::BIGINT
                ELSE (SELECT COUNT(*) FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id)
            END AS sort_key
        FROM public.plugins p
        WHERE public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
        AND (
            p_query = ''
            OR to_tsvector('english'::regconfig, p.display_name || ' ' || COALESCE(p.description, '')) @@ plainto_tsquery('english'::regconfig, p_query)
            OR p.plugin_id ILIKE '%' || p_query || '%'
        )
        AND (p_type IS NULL OR p.type = p_type)
        AND (p_verified_only = false OR p.verified = true)
        AND (
            p_tags IS NULL
            OR EXISTS (
                SELECT 1 FROM public.plugin_tags pt
                WHERE pt.plugin_id = p.id AND pt.tag = ANY(p_tags)
            )
        )
        AND (
            p_min_rating = 0
            OR COALESCE((SELECT AVG(pr.rating) FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id), 0) >= p_min_rating
        )
        ORDER BY sort_key DESC
        LIMIT p_page_size
        OFFSET v_offset
    ) AS subquery;

    RETURN QUERY SELECT COALESCE(v_plugins, '[]'::JSONB), v_total;
END;
$$;

ALTER FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") IS 'Shared body for search_plugins (viewer = auth.uid()) and search_plugins_for_viewer (viewer explicit). Not called directly: the two wrappers exist so the store''s public signature never changes while the service-role path can still name a viewer.';

REVOKE EXECUTE ON FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."search_plugins_internal"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") TO "service_role";


-- Signature IDENTICAL to the original. CREATE OR REPLACE, so anon/authenticated
-- grants survive and PostgREST resolution stays unambiguous.
CREATE OR REPLACE FUNCTION "public"."search_plugins"(
    "p_query" "text" DEFAULT ''::"text",
    "p_type" "text" DEFAULT NULL::"text",
    "p_tags" "text"[] DEFAULT NULL::"text"[],
    "p_min_rating" numeric DEFAULT 0,
    "p_verified_only" boolean DEFAULT false,
    "p_page" integer DEFAULT 1,
    "p_page_size" integer DEFAULT 20,
    "p_sort_by" "text" DEFAULT 'downloads'::"text"
) RETURNS TABLE("plugins" "jsonb", "total_count" bigint)
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT * FROM public.search_plugins_internal(
        auth.uid(), p_query, p_type, p_tags, p_min_rating, p_verified_only,
        p_page, p_page_size, p_sort_by);
$$;

ALTER FUNCTION "public"."search_plugins"("text","text","text"[],numeric,boolean,integer,integer,"text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."search_plugins"("text","text","text"[],numeric,boolean,integer,integer,"text") IS 'Plugin store search, scoped to what auth.uid() may see. Signature deliberately unchanged -- adding a parameter would create a second overload and PostgREST would fail every call with "function is not unique".';

REVOKE EXECUTE ON FUNCTION "public"."search_plugins"("text","text","text"[],numeric,boolean,integer,integer,"text") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."search_plugins"("text","text","text"[],numeric,boolean,integer,integer,"text") TO "anon", "authenticated", "service_role";


CREATE OR REPLACE FUNCTION "public"."search_plugins_for_viewer"(
    "p_viewer_id" "uuid",
    "p_query" "text" DEFAULT ''::"text",
    "p_type" "text" DEFAULT NULL::"text",
    "p_tags" "text"[] DEFAULT NULL::"text"[],
    "p_min_rating" numeric DEFAULT 0,
    "p_verified_only" boolean DEFAULT false,
    "p_page" integer DEFAULT 1,
    "p_page_size" integer DEFAULT 20,
    "p_sort_by" "text" DEFAULT 'downloads'::"text"
) RETURNS TABLE("plugins" "jsonb", "total_count" bigint)
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT * FROM public.search_plugins_internal(
        p_viewer_id, p_query, p_type, p_tags, p_min_rating, p_verified_only,
        p_page, p_page_size, p_sort_by);
$$;

ALTER FUNCTION "public"."search_plugins_for_viewer"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."search_plugins_for_viewer"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") IS 'Viewer-scoped search for the plugin-store edge function, which runs a service-role client where auth.uid() is NULL. Pass p_viewer_id NULL for an anonymous caller -- an explicit NULL beats relying on auth.uid() happening to be NULL. service_role only: exposing this to authenticated would let any user browse as anyone.';

REVOKE EXECUTE ON FUNCTION "public"."search_plugins_for_viewer"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."search_plugins_for_viewer"("uuid","text","text","text"[],numeric,boolean,integer,integer,"text") TO "service_role";


-- ============================================================================
-- SECTION 4: get_plugin_with_stats -- DROP required (return type changes)
-- ============================================================================

DROP FUNCTION IF EXISTS "public"."get_plugin_with_stats"("text");

CREATE OR REPLACE FUNCTION "public"."get_plugin_with_stats_internal"(
    "p_plugin_id" "text",
    "p_viewer_id" "uuid"
) RETURNS TABLE(
    "id" "uuid", "plugin_id" "text", "display_name" "text", "description" "text",
    "author_id" "uuid", "author_name" "text", "homepage_url" "text", "icon_url" "text",
    "type" "text", "api_version" "text", "verified" boolean, "published" boolean,
    "created_at" timestamp with time zone, "updated_at" timestamp with time zone,
    "latest_version" "text", "latest_version_id" "uuid",
    "avg_rating" numeric, "rating_count" bigint, "download_count" bigint,
    "tags" "text"[], "screenshots" "jsonb", "required_permissions" "text"[],
    "org_id" "uuid", "org_slug" "text", "visibility" "text"
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT
        p.id, p.plugin_id, p.display_name, p.description,
        p.author_id, p.author_name, p.homepage_url, p.icon_url,
        p.type, p.api_version, p.verified, p.published,
        p.created_at, p.updated_at,
        (SELECT pv.version FROM public.plugin_versions pv
          WHERE pv.plugin_id = p.id ORDER BY pv.published_at DESC LIMIT 1) AS latest_version,
        (SELECT pv.id FROM public.plugin_versions pv
          WHERE pv.plugin_id = p.id ORDER BY pv.published_at DESC LIMIT 1) AS latest_version_id,
        COALESCE((SELECT AVG(pr.rating)::NUMERIC(3,2) FROM public.plugin_ratings pr
                   WHERE pr.plugin_id = p.id), 0) AS avg_rating,
        (SELECT COUNT(*)::BIGINT FROM public.plugin_ratings pr WHERE pr.plugin_id = p.id) AS rating_count,
        (SELECT COUNT(*)::BIGINT FROM public.plugin_downloads pd WHERE pd.plugin_id = p.id) AS download_count,
        COALESCE((SELECT ARRAY_AGG(pt.tag) FROM public.plugin_tags pt
                   WHERE pt.plugin_id = p.id), ARRAY[]::TEXT[]) AS tags,
        COALESCE((
            SELECT jsonb_agg(jsonb_build_object('url', ps.url, 'caption', ps.caption)
                             ORDER BY ps.sort_order)
            FROM public.plugin_screenshots ps WHERE ps.plugin_id = p.id
        ), '[]'::JSONB) AS screenshots,
        COALESCE(p.required_permissions, ARRAY[]::TEXT[]) AS required_permissions,
        p.org_id,
        (SELECT o.slug FROM public.organisations o WHERE o.id = p.org_id) AS org_slug,
        p.visibility
    FROM public.plugins p
    WHERE p.plugin_id = p_plugin_id
      -- Was: p.published = true.
      AND public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published);
END;
$$;

ALTER FUNCTION "public"."get_plugin_with_stats_internal"("text","uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."get_plugin_with_stats_internal"("text","uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_plugin_with_stats_internal"("text","uuid") TO "service_role";


CREATE OR REPLACE FUNCTION "public"."get_plugin_with_stats"("p_plugin_id" "text")
RETURNS TABLE(
    "id" "uuid", "plugin_id" "text", "display_name" "text", "description" "text",
    "author_id" "uuid", "author_name" "text", "homepage_url" "text", "icon_url" "text",
    "type" "text", "api_version" "text", "verified" boolean, "published" boolean,
    "created_at" timestamp with time zone, "updated_at" timestamp with time zone,
    "latest_version" "text", "latest_version_id" "uuid",
    "avg_rating" numeric, "rating_count" bigint, "download_count" bigint,
    "tags" "text"[], "screenshots" "jsonb", "required_permissions" "text"[],
    "org_id" "uuid", "org_slug" "text", "visibility" "text"
)
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT * FROM public.get_plugin_with_stats_internal(p_plugin_id, auth.uid());
$$;

ALTER FUNCTION "public"."get_plugin_with_stats"("text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_plugin_with_stats"("text") IS 'Plugin detail, scoped to what auth.uid() may see. Dropped and recreated by 20260803000000 because the RETURNS TABLE gained org_id / org_slug / visibility -- CREATE OR REPLACE cannot change a return type. Grants re-issued below, including anon: the store browses anonymously.';

-- Re-issued because the DROP above removed them. Same pattern and reason as
-- 20260630000000. Omitting anon here would break anonymous store browsing.
REVOKE EXECUTE ON FUNCTION "public"."get_plugin_with_stats"("text") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."get_plugin_with_stats"("text") TO "anon", "authenticated", "service_role";


CREATE OR REPLACE FUNCTION "public"."get_plugin_with_stats_for_viewer"("p_plugin_id" "text", "p_viewer_id" "uuid")
RETURNS TABLE(
    "id" "uuid", "plugin_id" "text", "display_name" "text", "description" "text",
    "author_id" "uuid", "author_name" "text", "homepage_url" "text", "icon_url" "text",
    "type" "text", "api_version" "text", "verified" boolean, "published" boolean,
    "created_at" timestamp with time zone, "updated_at" timestamp with time zone,
    "latest_version" "text", "latest_version_id" "uuid",
    "avg_rating" numeric, "rating_count" bigint, "download_count" bigint,
    "tags" "text"[], "screenshots" "jsonb", "required_permissions" "text"[],
    "org_id" "uuid", "org_slug" "text", "visibility" "text"
)
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT * FROM public.get_plugin_with_stats_internal(p_plugin_id, p_viewer_id);
$$;

ALTER FUNCTION "public"."get_plugin_with_stats_for_viewer"("text","uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."get_plugin_with_stats_for_viewer"("text","uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_plugin_with_stats_for_viewer"("text","uuid") TO "service_role";


-- ============================================================================
-- SECTION 5: get_plugin_versions
-- ============================================================================
-- EASY TO MISS AND THE MOST DAMAGING IF MISSED: these rows carry jar_path and
-- sha256. Left ungated, an organisation-private plugin's artifact location is
-- world-readable through this function even after every other surface is closed.

CREATE OR REPLACE FUNCTION "public"."get_plugin_versions_internal"(
    "p_plugin_id" "text",
    "p_viewer_id" "uuid"
) RETURNS TABLE(
    "id" "uuid", "version" "text", "changelog" "text",
    "min_boss_version" "text", "min_ipc_version" "text", "min_api_version" "text",
    "jar_path" "text", "jar_size" bigint, "sha256" "text",
    "dependencies" "jsonb", "published_at" timestamp with time zone, "download_count" bigint
)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT
        pv.id, pv.version, pv.changelog,
        pv.min_boss_version, pv.min_ipc_version, pv.min_api_version,
        pv.jar_path, pv.jar_size, pv.sha256,
        pv.dependencies, pv.published_at,
        (SELECT COUNT(*)::bigint FROM public.plugin_downloads pd WHERE pd.version_id = pv.id) AS download_count
    FROM public.plugin_versions pv
    JOIN public.plugins p ON p.id = pv.plugin_id
    WHERE p.plugin_id = p_plugin_id
      -- Was: p.published = true.
      AND public.user_can_view_plugin_row(p_viewer_id, p.visibility, p.org_id, p.author_id, p.published)
    ORDER BY pv.published_at DESC;
END;
$$;

ALTER FUNCTION "public"."get_plugin_versions_internal"("text","uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."get_plugin_versions_internal"("text","uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_plugin_versions_internal"("text","uuid") TO "service_role";


-- Signature and return type unchanged, so CREATE OR REPLACE is safe here.
CREATE OR REPLACE FUNCTION "public"."get_plugin_versions"("p_plugin_id" "text")
RETURNS TABLE(
    "id" "uuid", "version" "text", "changelog" "text",
    "min_boss_version" "text", "min_ipc_version" "text", "min_api_version" "text",
    "jar_path" "text", "jar_size" bigint, "sha256" "text",
    "dependencies" "jsonb", "published_at" timestamp with time zone, "download_count" bigint
)
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT * FROM public.get_plugin_versions_internal(p_plugin_id, auth.uid());
$$;

ALTER FUNCTION "public"."get_plugin_versions"("text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_plugin_versions"("text") IS 'Version list for a plugin, scoped to what auth.uid() may see. These rows carry jar_path and sha256, so leaving this ungated would expose an organisation-private plugin''s artifact even with every other surface closed.';

REVOKE EXECUTE ON FUNCTION "public"."get_plugin_versions"("text") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."get_plugin_versions"("text") TO "anon", "authenticated", "service_role";


CREATE OR REPLACE FUNCTION "public"."get_plugin_versions_for_viewer"("p_plugin_id" "text", "p_viewer_id" "uuid")
RETURNS TABLE(
    "id" "uuid", "version" "text", "changelog" "text",
    "min_boss_version" "text", "min_ipc_version" "text", "min_api_version" "text",
    "jar_path" "text", "jar_size" bigint, "sha256" "text",
    "dependencies" "jsonb", "published_at" timestamp with time zone, "download_count" bigint
)
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT * FROM public.get_plugin_versions_internal(p_plugin_id, p_viewer_id);
$$;

ALTER FUNCTION "public"."get_plugin_versions_for_viewer"("text","uuid") OWNER TO "postgres";

REVOKE EXECUTE ON FUNCTION "public"."get_plugin_versions_for_viewer"("text","uuid") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."get_plugin_versions_for_viewer"("text","uuid") TO "service_role";


-- ============================================================================
-- SECTION 6: get_popular_tags -- a quiet leak
-- ============================================================================
-- This aggregates over plugin_tags for every PUBLISHED plugin, so once
-- organisation-private plugins exist their tags appear in the public tag cloud --
-- leaking project names to anyone browsing the store. Restricted to public
-- plugins, which keeps the result cacheable for everyone.

CREATE OR REPLACE FUNCTION "public"."get_popular_tags"("p_limit" integer DEFAULT 20)
RETURNS TABLE("tag" "text", "count" bigint)
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT pt.tag, COUNT(*)::BIGINT
    FROM public.plugin_tags pt
    JOIN public.plugins p ON p.id = pt.plugin_id
    WHERE p.published = true
      AND p.visibility = 'public'
    GROUP BY pt.tag
    ORDER BY COUNT(*) DESC
    LIMIT p_limit;
END;
$$;

ALTER FUNCTION "public"."get_popular_tags"(integer) OWNER TO "postgres";

COMMENT ON FUNCTION "public"."get_popular_tags"(integer) IS 'Popular tags across PUBLIC plugins only. Organisation-private plugin tags are excluded so the public tag cloud cannot leak internal project names.';

REVOKE EXECUTE ON FUNCTION "public"."get_popular_tags"(integer) FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."get_popular_tags"(integer) TO "anon", "authenticated", "service_role";


-- ============================================================================
-- SECTION 7: validate_plugin_api_key -- return the bound organisation
-- ============================================================================

DROP FUNCTION IF EXISTS "public"."validate_plugin_api_key"("text");

CREATE OR REPLACE FUNCTION "public"."validate_plugin_api_key"("p_key_hash" "text")
RETURNS TABLE("key_id" "uuid", "user_id" "uuid", "scopes" "text"[], "org_id" "uuid")
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    RETURN QUERY
    SELECT k.id, k.user_id, k.scopes,
           -- NULL is treated as the boss organisation, matching the backfill, so a
           -- key minted before this migration keeps publishing.
           COALESCE(k.org_id, (SELECT o.id FROM public.organisations o WHERE o.slug = 'boss'))
    FROM public.plugin_api_keys k
    WHERE k.key_hash = p_key_hash
      AND k.revoked_at IS NULL
      AND (k.expires_at IS NULL OR k.expires_at > now());
END;
$$;

ALTER FUNCTION "public"."validate_plugin_api_key"("text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."validate_plugin_api_key"("text") IS 'Resolves a plugin-store API key to its owner, scopes and bound organisation. org_id falls back to the boss organisation so keys minted before 20260803000000 keep working. The publish path must gate on user_can_publish_org_plugin(user_id, org_id) -- the key is not an independent principal, so revoking the human''s membership kills their CI key''s publish rights too.';

REVOKE EXECUTE ON FUNCTION "public"."validate_plugin_api_key"("text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."validate_plugin_api_key"("text") TO "service_role";


-- ============================================================================
-- SECTION 8: RLS
-- ============================================================================
-- These govern DIRECT PostgREST access (and the desktop client's realtime
-- subscription). The plugin-store edge function uses the service role and bypasses
-- them entirely, which is exactly why routes/download.ts must call
-- user_can_view_plugin explicitly -- a policy that looks enforced is not the same
-- as one that is.

DROP POLICY IF EXISTS "Published plugins are viewable by everyone" ON "public"."plugins";
DROP POLICY IF EXISTS "Visible plugins are viewable" ON "public"."plugins";
CREATE POLICY "Visible plugins are viewable" ON "public"."plugins"
    FOR SELECT USING (
        "public"."can_view_plugin_row"(
            "visibility", "org_id", "author_id", "published")
    );

-- Publishing for an organisation must satisfy its publish policy.
DROP POLICY IF EXISTS "Authenticated users can create plugins" ON "public"."plugins";
DROP POLICY IF EXISTS "Authorised publishers can create plugins" ON "public"."plugins";
CREATE POLICY "Authorised publishers can create plugins" ON "public"."plugins"
    FOR INSERT TO "authenticated"
    WITH CHECK (
        "auth"."uid"() = "author_id"
        AND ("org_id" IS NULL OR "public"."can_publish_org_plugin"("org_id"))
    );

DROP POLICY IF EXISTS "Authors can update own plugins" ON "public"."plugins";
DROP POLICY IF EXISTS "Authors and organisation admins can update plugins" ON "public"."plugins";
CREATE POLICY "Authors and organisation admins can update plugins" ON "public"."plugins"
    FOR UPDATE TO "authenticated"
    USING (
        "auth"."uid"() = "author_id"
        OR ("org_id" IS NOT NULL AND "public"."is_org_admin"("org_id"))
    )
    WITH CHECK (
        "auth"."uid"() = "author_id"
        OR ("org_id" IS NOT NULL AND "public"."is_org_admin"("org_id"))
    );

-- Child tables: replace the bare "published" test with the full visibility rule,
-- or an organisation plugin's versions, tags and screenshots stay world-readable.
DROP POLICY IF EXISTS "Published versions are viewable" ON "public"."plugin_versions";
CREATE POLICY "Published versions are viewable" ON "public"."plugin_versions"
    FOR SELECT USING (EXISTS (
        SELECT 1 FROM "public"."plugins" p
        WHERE p."id" = "plugin_versions"."plugin_id"
          AND "public"."can_view_plugin_row"(
              p."visibility", p."org_id", p."author_id", p."published")
    ));

DROP POLICY IF EXISTS "Published tags are viewable" ON "public"."plugin_tags";
CREATE POLICY "Published tags are viewable" ON "public"."plugin_tags"
    FOR SELECT USING (EXISTS (
        SELECT 1 FROM "public"."plugins" p
        WHERE p."id" = "plugin_tags"."plugin_id"
          AND "public"."can_view_plugin_row"(
              p."visibility", p."org_id", p."author_id", p."published")
    ));

DROP POLICY IF EXISTS "Published screenshots are viewable" ON "public"."plugin_screenshots";
CREATE POLICY "Published screenshots are viewable" ON "public"."plugin_screenshots"
    FOR SELECT USING (EXISTS (
        SELECT 1 FROM "public"."plugins" p
        WHERE p."id" = "plugin_screenshots"."plugin_id"
          AND "public"."can_view_plugin_row"(
              p."visibility", p."org_id", p."author_id", p."published")
    ));


-- ============================================================================
-- SECTION 9: Follow-ups this migration deliberately does NOT do
-- ============================================================================
-- 1. ALTER TABLE plugins ALTER COLUMN org_id SET NOT NULL -- once the production
--    backfill is confirmed AND a boss organisation is known to exist. Doing it
--    here would fail the deploy on a database whose seed was skipped.
-- 2. The plugins table is in the supabase_realtime publication
--    (20260216000000_enable_realtime_plugin_store.sql). The new SELECT policy is
--    what stops a signed-in client's subscription streaming private organisation
--    plugin rows -- VERIFY that in a client after deploying, because realtime
--    RLS behaviour is easy to assume and hard to notice when wrong.
-- 3. plugins_with_latest_version is security_invoker (20260307000002), so it
--    inherits the new SELECT policy automatically. Its anon grant now yields
--    public plugins only. No view change needed -- but that IS a behaviour change
--    for any consumer that expected everything.
-- 4. The edge function still has to be changed: routes/download.ts needs the
--    user_can_view_plugin gate (RLS cannot help there), routes/browse.ts needs the
--    *_for_viewer variants plus private cache headers, and routes/publish.ts needs
--    user_can_publish_org_plugin. Until then this migration is inert, which is the
--    intended rollout order.


-- ============================================================================
-- End of File: 20260803000000_plugins_org_ownership.sql
-- ============================================================================
