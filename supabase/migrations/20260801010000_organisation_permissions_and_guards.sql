-- ============================================================================
-- BOSS Database Schema: Organisation permissions, predicates and escalation guards
-- ============================================================================
-- File: 20260801010000_organisation_permissions_and_guards.sql
-- Description:
--   The authorization core of the organisation feature. Four parts:
--     1. The organisation.* permission catalog (system permissions).
--     2. Slug / role-name helpers -- the single source of truth for how an
--        organisation's role names are derived, and which slugs are refused.
--     3. The membership predicates every org gate resolves through.
--     4. Two triggers that structurally prevent the escalation paths that
--        slug-derived role names would otherwise open.
--
-- Dependencies:
--   - 20260801000000_organisation_tables.sql
--   - 20251023000008_rbac_tables.sql            (roles, permissions, role_permissions)
--   - 20260625000000_role_hierarchy_and_granular_rbac.sql
--                                               (authorize, get_role_descendants,
--                                                get_grantable_role_ids)
--   - 20260629000000_plugin_defined_permissions.sql (register_plugin_permission)
--   - 20251023000006_user_functions.sql         (is_user_admin)
--
-- Next migration: 20260801020000_organisation_rls.sql
-- ============================================================================


-- ============================================================================
-- SECTION 1: Permission catalog
-- ============================================================================
-- All four names satisfy create_new_permission's regex
--   ^[a-z][a-z0-9_]{1,30}\.[a-z][a-z0-9_]{1,30}$
-- ("organisation" is 12 characters, well inside the 31-char domain limit).
--
-- READ THIS BEFORE USING organisation.admin ANYWHERE:
--   authorize('organisation.admin') answers "does this user hold it ANYWHERE",
--   and short-circuits true for global admins. It is therefore USELESS as an
--   org-scoped gate -- a member of org A would pass a check for org B. The
--   permission exists only so that an org admin role can CARRY it, which puts
--   it in the user_permissions JWT claim and lets the desktop UI render an
--   admin affordance without a round trip. The authorization decision is always
--   public.is_org_admin(org_id) / public.is_org_member(org_id) from SECTION 3.

INSERT INTO "public"."permissions" ("name", "description", "is_system") VALUES
    ('organisation.create',
     'Submit a request to create a new organisation',
     true),
    ('organisation.approve',
     'Review, approve and reject organisation-creation requests (global BOSS reviewers)',
     true),
    ('organisation.admin',
     'Carried by an organisation admin role so the UI can render admin affordances. NOT an authorization decision -- org-scoped gates use is_org_admin(org_id), which reads the membership tables.',
     true),
    ('organisation.read',
     'List and inspect the organisations you belong to',
     true)
ON CONFLICT ("name") DO NOTHING;


-- organisation.create + organisation.read go to the baseline `user` role, so
-- every authenticated user may request an organisation. Revoking the grant from
-- `user` is the global kill-switch for self-service organisation creation:
--   SELECT public.remove_permission_from_role('user', 'organisation.create');
--
-- organisation.approve goes to admin and boss_admin. organisation.admin is
-- deliberately granted to NO global role here -- create_organisation_internal
-- attaches it to each org's own admin role.
INSERT INTO "public"."role_permissions" ("role_id", "permission_id")
SELECT r."id", p."id"
FROM (VALUES
    ('user',       'organisation.create'),
    ('user',       'organisation.read'),
    ('admin',      'organisation.approve'),
    ('boss_admin', 'organisation.approve')
) AS grant_map("role_name", "perm_name")
JOIN "public"."roles" r       ON r."name" = grant_map."role_name"
JOIN "public"."permissions" p ON p."name" = grant_map."perm_name"
ON CONFLICT ("role_id", "permission_id") DO NOTHING;


-- ============================================================================
-- SECTION 2: Reserve the organisation.* domain against plugin-defined permissions
-- ============================================================================
-- Body is byte-identical to 20260629000000 apart from the reserved-domain list.
--
-- Why this matters: without it, a plugin manifest may declare
--   definedPermissions: [{ name: "organisation.admin" }]
-- and have it auto-registered into the catalog as a NON-system permission at
-- publish time. An admin browsing the role UI would then see a permission that
-- looks like it confers organisation powers and might grant it. The name would
-- collide with the real system permission on the UNIQUE index -- but any other
-- organisation.* name would not, and would be pure confusion.
--
-- Compatibility: a plugin already declaring an organisation.* permission would
-- start failing at publish. A grep over boss_plugins finds none.

CREATE OR REPLACE FUNCTION "public"."register_plugin_permission"(
    "p_name" "text",
    "p_description" "text" DEFAULT NULL::"text",
    "p_plugin_id" "text" DEFAULT NULL::"text"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $_$
DECLARE
    v_domain TEXT;
    v_existing_id UUID;
    v_existing_is_system BOOLEAN;
    v_permission_id UUID;
BEGIN
    -- Format: domain.action (mirror create_new_permission)
    IF p_name IS NULL OR NOT (p_name ~ '^[a-z][a-z0-9_]{1,30}\.[a-z][a-z0-9_]{1,30}$') THEN
        RETURN jsonb_build_object('success', false, 'error', 'Invalid permission format');
    END IF;

    -- Reserved system domains are off-limits to plugin-defined permissions.
    -- 'organisation' and 'org' added by 20260801010000: the organisation.*
    -- catalog is owned by that migration as SYSTEM permissions, and a
    -- plugin-defined lookalike would be a social-engineering surface.
    v_domain := split_part(p_name, '.', 1);
    IF v_domain IN ('role', 'user', 'api_key', 'rpa', 'secret', 'plugins', 'organisation', 'org') THEN
        RETURN jsonb_build_object('success', false, 'error',
            format('Reserved permission domain "%s"', v_domain));
    END IF;

    SELECT id, is_system INTO v_existing_id, v_existing_is_system
    FROM public.permissions WHERE name = p_name;

    IF FOUND THEN
        -- Never touch / re-own a core (system) permission.
        IF v_existing_is_system THEN
            RETURN jsonb_build_object('success', false, 'error',
                format('Permission "%s" is a system permission', p_name));
        END IF;
        -- Already registered as a plugin permission: ensure provenance, idempotently.
        INSERT INTO public.plugin_permissions (permission_id, plugin_id)
        VALUES (v_existing_id, p_plugin_id)
        ON CONFLICT (permission_id) DO NOTHING;
        RETURN jsonb_build_object('success', true, 'created', false,
            'permission_id', v_existing_id::text, 'permission', p_name);
    END IF;

    -- New permission: non-system, UNGRANTED.
    INSERT INTO public.permissions (name, description, is_system)
    VALUES (p_name, p_description, false)
    RETURNING id INTO v_permission_id;

    INSERT INTO public.plugin_permissions (permission_id, plugin_id)
    VALUES (v_permission_id, p_plugin_id)
    ON CONFLICT (permission_id) DO NOTHING;

    RETURN jsonb_build_object('success', true, 'created', true,
        'permission_id', v_permission_id::text, 'permission', p_name);
END;
$_$;

REVOKE EXECUTE ON FUNCTION "public"."register_plugin_permission"("text", "text", "text") FROM PUBLIC, "anon", "authenticated";
GRANT EXECUTE ON FUNCTION "public"."register_plugin_permission"("text", "text", "text") TO "service_role";


-- ============================================================================
-- SECTION 3: Slug and role-name helpers
-- ============================================================================

-- organisation_role_name: THE single source of truth for how an organisation's
-- role names are derived from its slug. Everything else calls this. To move to
-- a universal `org_<slug>_` prefix later, change this body -- nothing else
-- depends on the shape, because organisation_roles is what makes a role an org
-- role (see the boss org, whose roles are boss_org_admin / boss_org_user).
CREATE OR REPLACE FUNCTION "public"."organisation_role_name"("p_slug" "text", "p_kind" "text")
RETURNS "text"
    LANGUAGE "sql" IMMUTABLE
    SET "search_path" TO ''
    AS $$
    SELECT p_slug || '_' || p_kind;
$$;

ALTER FUNCTION "public"."organisation_role_name"("text", "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."organisation_role_name"("text", "text") IS 'Derives an organisation role name from its slug, e.g. (acme, admin) -> acme_admin. The single place the naming shape is defined.';

-- REVOKE first: 20251023000014_grants.sql sets ALTER DEFAULT PRIVILEGES ... GRANT ALL ON
-- FUNCTIONS TO anon, so every function here is anon-executable the moment it is created
-- and a bare GRANT to authenticated does not take that away. Same trap as ON TABLES,
-- caught there and missed here. None of these were an authorization hole - they all
-- resolve through resolve_org_actor, which returns NULL for anon - but an
-- unauthenticated DB-reachable surface nobody intended is worth closing.
REVOKE EXECUTE ON FUNCTION "public"."organisation_role_name"("text", "text") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."organisation_role_name"("text", "text") TO "authenticated", "service_role";


-- is_reserved_organisation_slug: refuses slugs that would collide with the
-- global role tiers, with product surface, or -- the important arm -- whose
-- DERIVED role names are already taken.
--
-- The derived-name check is the primary defence against H2. Slug 'boss' derives
-- 'boss_admin', which already exists as a GLOBAL SYSTEM role carrying
-- role.create / role.assign / plugins.admin.*; mapping it into
-- organisation_roles would hand every admin of that org global powers. Slug
-- 'finance' derives 'finance_admin', same problem. The static list below is
-- belt; this EXISTS clause is braces; and enforce_org_role_not_system
-- (SECTION 4) is the structural backstop if both were somehow bypassed.
CREATE OR REPLACE FUNCTION "public"."is_reserved_organisation_slug"("p_slug" "text")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT p_slug IS NULL
       OR p_slug IN (
            -- role tiers and authorization vocabulary
            'boss', 'admin', 'administrator', 'user', 'users', 'root', 'system',
            'internal', 'finance', 'boss_plugin', 'service_role', 'anon',
            'authenticated', 'postgres', 'supabase',
            -- organisation vocabulary (would produce confusing role names)
            'org', 'orgs', 'organisation', 'organisations', 'organization', 'organizations',
            -- product / routing surface
            'api', 'www', 'app', 'auth', 'login', 'logout', 'signup', 'store',
            'plugin', 'plugins', 'secret', 'secrets', 'settings', 'support',
            'security', 'billing', 'help', 'docs', 'status', 'health',
            -- sentinels that read badly in a URL or a role name
            'staging', 'test', 'none', 'null', 'new', 'me', 'all'
       )
       -- The derived role names must both be free. This is the H2 guard.
       OR EXISTS (
            SELECT 1 FROM public.roles r
            WHERE r.name = public.organisation_role_name(p_slug, 'admin')
               OR r.name = public.organisation_role_name(p_slug, 'user')
       );
$$;

ALTER FUNCTION "public"."is_reserved_organisation_slug"("text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."is_reserved_organisation_slug"("text") IS 'True when a slug may not be used for a new organisation: a reserved name, or one whose derived <slug>_admin / <slug>_user role name already exists. The derived-name arm is what stops slug "boss" mapping the GLOBAL boss_admin role into an organisation.';

REVOKE EXECUTE ON FUNCTION "public"."is_reserved_organisation_slug"("text") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."is_reserved_organisation_slug"("text") TO "authenticated", "service_role";


-- ============================================================================
-- SECTION 4: Membership predicates -- the authorization core
-- ============================================================================
-- Every one of these is SECURITY DEFINER, and that is load-bearing for two
-- separate reasons:
--
--   1. RLS SELF-RECURSION. A policy on organisation_members that did
--      EXISTS (SELECT 1 FROM organisation_members ...) raises
--      "infinite recursion detected in policy for relation". SECURITY DEFINER
--      functions bypass RLS on the tables they read, which is exactly the trick
--      is_user_admin() already plays for user_roles.
--   2. The org tables grant authenticated SELECT only, and the policies in the
--      next migration are themselves written in terms of these functions.
--
-- Two families:
--   * is_org_*(org_id)                 -- resolve the subject from auth.uid().
--                                         Safe to expose to authenticated.
--   * user_is_org_*(user_id, org_id)   -- take an arbitrary subject. REVOKED
--                                         from authenticated: an authenticated
--                                         caller must never be able to ask
--                                         "is SOMEONE ELSE an admin of X".
--                                         Granted to service_role (the edge
--                                         function, which holds no user JWT)
--                                         and to supabase_auth_admin (the JWT
--                                         hook).
--
-- GLOBAL ADMINS ARE ORG ADMINS EVERYWHERE, by design. That mirrors
-- authorize()'s existing short-circuit and keeps the server in agreement with
-- the desktop client, whose UserInfo.hasPermission also short-circuits for
-- admins. It is a deliberate, documented property -- not an oversight.

CREATE OR REPLACE FUNCTION "public"."user_is_org_member"("p_user_id" "uuid", "p_org_id" "uuid")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT p_user_id IS NOT NULL AND p_org_id IS NOT NULL AND (
        public.is_user_admin(p_user_id)
        OR EXISTS (
            SELECT 1 FROM public.organisation_members m
            WHERE m.org_id = p_org_id
              AND m.user_id = p_user_id
              AND m.status = 'active'
        )
    );
$$;

ALTER FUNCTION "public"."user_is_org_member"("uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."user_is_org_member"("uuid", "uuid") IS 'Whether a given user is an ACTIVE member of an organisation (pending/invited do not count). Global admins are members everywhere, mirroring authorize(). service_role/auth-hook only -- authenticated callers use is_org_member(org_id).';


CREATE OR REPLACE FUNCTION "public"."user_is_org_admin"("p_user_id" "uuid", "p_org_id" "uuid")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT p_user_id IS NOT NULL AND p_org_id IS NOT NULL AND (
        public.is_user_admin(p_user_id)
        OR EXISTS (
            SELECT 1 FROM public.organisations o
            WHERE o.id = p_org_id AND o.owner_id = p_user_id
        )
        OR EXISTS (
            SELECT 1
            FROM public.organisation_members m
            JOIN public.organisation_roles orl
              ON orl.org_id = m.org_id AND orl.kind = 'admin'
            JOIN public.user_roles ur
              ON ur.role_id = orl.role_id AND ur.user_id = m.user_id
            WHERE m.org_id = p_org_id
              AND m.user_id = p_user_id
              AND m.status = 'active'
        )
    );
$$;

ALTER FUNCTION "public"."user_is_org_admin"("uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."user_is_org_admin"("uuid", "uuid") IS 'Whether a given user administers an organisation: a global admin, the org owner, or an ACTIVE member holding the org''s admin-kind role. THE org-scoped authorization decision -- never substitute authorize(''organisation.admin''), which is org-blind. service_role/auth-hook only.';


CREATE OR REPLACE FUNCTION "public"."is_org_member"("p_org_id" "uuid")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT public.user_is_org_member(auth.uid(), p_org_id);
$$;

ALTER FUNCTION "public"."is_org_member"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."is_org_member"("uuid") IS 'Whether the CURRENT user is an active member of an organisation. Safe for RLS and for authenticated callers -- the subject is always auth.uid().';


CREATE OR REPLACE FUNCTION "public"."is_org_admin"("p_org_id" "uuid")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT public.user_is_org_admin(auth.uid(), p_org_id);
$$;

ALTER FUNCTION "public"."is_org_admin"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."is_org_admin"("uuid") IS 'Whether the CURRENT user administers an organisation. THE gate for every org-scoped mutation. Never replace with authorize(''organisation.admin'').';


-- ----------------------------------------------------------------------------
-- resolve_org_actor: who is acting, for functions callable BOTH by a signed-in
-- user and by the organisation edge function.
-- ----------------------------------------------------------------------------
-- The problem it solves: the `organisation` edge function serves the org page
-- and the admin-configuration page. It holds NO user JWT -- a handoff token
-- yields a user_id, not a session -- so it must use its service-role client,
-- under which auth.uid() is NULL. Every org RPC would then either reject it or,
-- far worse, run unauthorized.
--
-- Alternatives considered and rejected:
--   * A parallel *_for_actor(p_actor_id, ...) variant of each org RPC: ~17
--     near-duplicate functions, i.e. two copies of every gate to keep in step.
--   * Minting a real user session server-side (admin.generateLink + verifyOtp,
--     as functions/passkey/utils/jwt.ts does): burns GoTrue's per-IP
--     token_verifications limit -- an edge function is ONE ip -- and leaves an
--     unrevoked auth.sessions row per page open.
--   * Authorizing in TypeScript and writing tables with the service role:
--     duplicates publish_policy/join_policy semantics in a second language and
--     bypasses every RLS-enforced invariant.
--
-- What we do instead: each gated org RPC takes a trailing
-- `p_actor_id uuid DEFAULT NULL` and resolves the actor through this function.
-- p_actor_id is HONOURED ONLY for a service_role caller. For everyone else
-- auth.uid() wins and the parameter is ignored entirely, so an authenticated
-- user passing p_actor_id => '<someone else>' changes nothing.
--
-- Why the service_role test is trustworthy: PostgREST/GoTrue verify the JWT
-- signature before exposing its claims, and only the service-role key carries
-- role=service_role. The repo already leans on exactly this predicate in
-- 20251023000013_rls_policies.sql ("Service role full access" uses
-- auth.jwt() ->> 'role' = 'service_role').
CREATE OR REPLACE FUNCTION "public"."resolve_org_actor"("p_actor_id" "uuid" DEFAULT NULL::"uuid")
RETURNS "uuid"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_uid UUID;
BEGIN
    v_uid := auth.uid();

    -- A signed-in caller is always themselves. p_actor_id is ignored.
    IF v_uid IS NOT NULL THEN
        RETURN v_uid;
    END IF;

    -- No session: only the service role may name an actor, and only one that
    -- actually exists.
    IF COALESCE(auth.jwt() ->> 'role', '') = 'service_role'
       AND p_actor_id IS NOT NULL
       AND EXISTS (SELECT 1 FROM auth.users u WHERE u.id = p_actor_id) THEN
        RETURN p_actor_id;
    END IF;

    RETURN NULL;
END;
$$;

ALTER FUNCTION "public"."resolve_org_actor"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."resolve_org_actor"("uuid") IS 'Resolves the acting user for an organisation RPC. Returns auth.uid() when there is a session; otherwise returns p_actor_id ONLY for a service_role caller (the organisation edge function, which holds a handoff-derived user_id rather than a JWT). Returns NULL when neither applies, so callers fail closed. An authenticated caller can never impersonate via p_actor_id -- auth.uid() takes precedence unconditionally.';

REVOKE EXECUTE ON FUNCTION "public"."resolve_org_actor"("uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."resolve_org_actor"("uuid") TO "authenticated", "service_role";


-- user_holds_permission: the actor-aware counterpart of authorize().
--
-- authorize() resolves its subject from auth.uid(), so under the edge function's
-- service-role client it answers for nobody and returns false. The two genuinely
-- global organisation gates (organisation.create, organisation.approve) therefore
-- need a form that takes an explicit subject.
--
-- Semantics mirror authorize() exactly, including the admin short-circuit, so
-- there is one answer to "does this user hold P" regardless of which entry point
-- asks.
--
-- Named user_holds_permission, not user_has_permission: a work-in-progress
-- migration on another branch introduces public.user_has_permission(uuid, text)
-- and this must not collide with it whichever lands first.
CREATE OR REPLACE FUNCTION "public"."user_holds_permission"("p_user_id" "uuid", "p_permission" "text")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT p_user_id IS NOT NULL AND p_permission IS NOT NULL AND (
        public.is_user_admin(p_user_id)
        OR p_permission = ANY (public.get_effective_permissions(p_user_id))
    );
$$;

ALTER FUNCTION "public"."user_holds_permission"("uuid", "text") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."user_holds_permission"("uuid", "text") IS 'Whether a given user holds a permission, expanding the role hierarchy exactly as authorize() does and short-circuiting for global admins. The actor-aware form of authorize(), for paths where the subject is not auth.uid() -- i.e. the organisation edge function. service_role only.';

REVOKE EXECUTE ON FUNCTION "public"."user_holds_permission"("uuid", "text") FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."user_holds_permission"("uuid", "text") TO "service_role";


-- user_can_publish_org_plugin: the whole publish-policy decision, in one place.
-- The plugin-store edge function calls it through the service role rather than
-- re-implementing publish_policy in TypeScript, so the two paths cannot
-- diverge.
CREATE OR REPLACE FUNCTION "public"."user_can_publish_org_plugin"("p_user_id" "uuid", "p_org_id" "uuid")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT CASE
        WHEN p_user_id IS NULL OR p_org_id IS NULL THEN false
        WHEN public.is_user_admin(p_user_id) THEN true
        WHEN NOT public.user_is_org_member(p_user_id, p_org_id) THEN false
        ELSE (
            SELECT CASE
                -- publish_role_id, when set, OVERRIDES publish_policy entirely.
                WHEN o.publish_role_id IS NOT NULL THEN EXISTS (
                    SELECT 1 FROM public.user_roles ur
                    WHERE ur.user_id = p_user_id AND ur.role_id = o.publish_role_id
                )
                WHEN o.publish_policy = 'owner_only' THEN o.owner_id = p_user_id
                WHEN o.publish_policy = 'admins'     THEN public.user_is_org_admin(p_user_id, o.id)
                -- 'members': membership was already established above.
                WHEN o.publish_policy = 'members'    THEN true
                ELSE false
            END
            FROM public.organisations o WHERE o.id = p_org_id
        )
    END;
$$;

ALTER FUNCTION "public"."user_can_publish_org_plugin"("uuid", "uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."user_can_publish_org_plugin"("uuid", "uuid") IS 'Whether a user may publish a plugin owned by an organisation, evaluating publish_role_id (which overrides) then publish_policy. The single source of truth -- the plugin-store edge function calls this rather than duplicating the policy in TypeScript.';


CREATE OR REPLACE FUNCTION "public"."can_publish_org_plugin"("p_org_id" "uuid")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT public.user_can_publish_org_plugin(auth.uid(), p_org_id);
$$;

ALTER FUNCTION "public"."can_publish_org_plugin"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."can_publish_org_plugin"("uuid") IS 'Whether the CURRENT user may publish for an organisation. Used by the plugins RLS WITH CHECK.';


-- Exposure. Mirrors 20260625000000 SECTION 9: the auth.uid()-based helpers are
-- safe for authenticated; the arbitrary-subject ones are not.
REVOKE EXECUTE ON FUNCTION "public"."is_org_member"("uuid") FROM PUBLIC, "anon";
GRANT  EXECUTE ON FUNCTION "public"."is_org_member"("uuid")           TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."is_org_admin"("uuid") FROM PUBLIC, "anon";
GRANT  EXECUTE ON FUNCTION "public"."is_org_admin"("uuid")            TO "authenticated", "service_role";
REVOKE EXECUTE ON FUNCTION "public"."can_publish_org_plugin"("uuid") FROM PUBLIC, "anon";
GRANT  EXECUTE ON FUNCTION "public"."can_publish_org_plugin"("uuid")  TO "authenticated", "service_role";

REVOKE EXECUTE ON FUNCTION "public"."user_is_org_member"("uuid", "uuid")           FROM PUBLIC, "anon", "authenticated";
REVOKE EXECUTE ON FUNCTION "public"."user_is_org_admin"("uuid", "uuid")            FROM PUBLIC, "anon", "authenticated";
REVOKE EXECUTE ON FUNCTION "public"."user_can_publish_org_plugin"("uuid", "uuid")  FROM PUBLIC, "anon", "authenticated";

GRANT  EXECUTE ON FUNCTION "public"."user_is_org_member"("uuid", "uuid")           TO "service_role", "supabase_auth_admin";
GRANT  EXECUTE ON FUNCTION "public"."user_is_org_admin"("uuid", "uuid")            TO "service_role", "supabase_auth_admin";
GRANT  EXECUTE ON FUNCTION "public"."user_can_publish_org_plugin"("uuid", "uuid")  TO "service_role";


-- ============================================================================
-- SECTION 5: Escalation guards
-- ============================================================================

-- ----------------------------------------------------------------------------
-- Guard 1 (H2): an organisation role can never be a global/system role.
-- ----------------------------------------------------------------------------
-- The structural backstop behind is_reserved_organisation_slug's derived-name
-- check. Even if a name check were bypassed or a future code path inserted into
-- organisation_roles directly, `user`, `admin`, `boss_admin`, `finance_admin`
-- and `boss_plugin_admin` can never become an organisation's admin, member or
-- custom role.
CREATE OR REPLACE FUNCTION "public"."enforce_org_role_not_system"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_name TEXT;
    v_is_system BOOLEAN;
BEGIN
    SELECT r.name, r.is_system INTO v_name, v_is_system
    FROM public.roles r WHERE r.id = NEW.role_id;

    IF NOT FOUND THEN
        RAISE EXCEPTION 'organisation_roles: role % does not exist', NEW.role_id;
    END IF;

    IF v_is_system THEN
        RAISE EXCEPTION 'organisation_roles: cannot map system role "%" to an organisation', v_name;
    END IF;

    -- The role's EXISTING permissions must also satisfy Guard 2.
    --
    -- Guard 2 lives on role_permissions, so on its own it only constrains
    -- permissions attached AFTER the mapping. Without this clause the sequence
    -- "create a plain role, grant it role.assign, then map it into an
    -- organisation" walks straight past both guards. Every caller today creates
    -- the role fresh and maps before granting, so it is not reachable now - but
    -- one future "adopt an existing role into an organisation" RPC would make it
    -- so, and this trigger is exactly where that belongs.
    IF EXISTS (
        SELECT 1
        FROM public.role_permissions rp
        WHERE rp.role_id = NEW.role_id
          AND NOT public.is_org_grantable_permission(rp.permission_id)
    ) THEN
        RAISE EXCEPTION
            'organisation_roles: role "%" already holds a permission an organisation role may not carry',
            v_name;
    END IF;

    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."enforce_org_role_not_system"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."enforce_org_role_not_system"() IS 'Refuses to map into an organisation any role that is a system role, or that already holds a permission Guard 2 forbids. The second clause matters because Guard 2 fires on role_permissions, so it alone only constrains permissions attached AFTER the mapping.';

DROP TRIGGER IF EXISTS "enforce_org_role_not_system" ON "public"."organisation_roles";
CREATE TRIGGER "enforce_org_role_not_system"
    BEFORE INSERT OR UPDATE ON "public"."organisation_roles"
    FOR EACH ROW EXECUTE FUNCTION "public"."enforce_org_role_not_system"();


-- ----------------------------------------------------------------------------
-- Guard 2 (H3): an allowlist of permissions an org-mapped role may hold.
-- ----------------------------------------------------------------------------
-- This is the important one. It kills an entire escalation class outright.
--
-- The problem: create_organisation_internal adds the hierarchy edge
-- <slug>_user -> user, so the global `user` role becomes a strict descendant of
-- the org's roles. get_grantable_role_ids returns strict descendants, so an org
-- admin's grantable set contains `user`. Combined with role.update that is an
-- "attach a permission to `user` = grant it to every BOSS user" primitive; with
-- role.assign it is worse.
--
-- The fix is not to police who may grant, but to make the dangerous
-- permissions unreachable BY an org role at all.
--
-- ############################################################################
-- ## DO NOT use permissions.is_system as the test. IT IS NOT RELIABLE HERE.  ##
-- ############################################################################
-- Verified against the actual catalog, not inferred from the seed migrations:
--
--   is_system = FALSE : api_key.create, finance.*, role.assign, role.create,
--                       role.delete, role.update, rpa.write
--   is_system = TRUE  : organisation.*, plugins.admin.*, role.read, secret.read,
--                       user.delete, user.read, user.update, user.write
--
-- The flag is inconsistent, and it is inconsistent in the WORST direction: the
-- role-escalation permissions (role.assign, role.create, role.update) are all
-- is_system = false. An "allow anything non-system" rule would therefore have
-- permitted exactly the grant this guard exists to stop -- and the first version
-- of this function did, which a behavioural test caught.
--
-- So the test is the permission's DOMAIN, which is structural and cannot drift:
-- a reserved-domain deny-list, mirroring the convention register_plugin_permission
-- already established (20260629000000), plus a short explicit name allowlist for
-- the few reserved-domain permissions an organisation legitimately holds.
--
-- Note plugins.admin.* has two dots, so its domain is 'plugins' and it is denied.
--
-- FAILS CLOSED: an unknown permission id matches no row and is refused; a name
-- with no dot yields a domain equal to itself and is refused unless allowlisted.
--
-- SCOPE: this trigger applies to EVERYONE -- global admins and service_role
-- included. A global admin cannot grant role.assign to acme_admin through the
-- Admin: Roles UI either; the RPC surfaces this exception. That is intentional,
-- and belongs in the release notes so it is not mistaken for a bug.
CREATE OR REPLACE FUNCTION "public"."is_org_grantable_permission"("p_permission_id" "uuid")
RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT EXISTS (
        SELECT 1 FROM public.permissions p
        WHERE p.id = p_permission_id
          AND (
              -- The few reserved-domain permissions an organisation may hold.
              p.name IN (
                  'organisation.admin',   -- carried by the org admin role (a UI hint)
                  'organisation.read',    -- baseline: see the org you belong to
                  'secret.read',          -- org-shared secrets
                  'plugins.create'        -- publish plugins the org owns
              )
              -- Otherwise: any permission outside the reserved platform domains.
              -- Plugin-defined permissions live in their own domains
              -- (docker.manage, k8s.read, ...) and are an organisation's business.
              OR split_part(p.name, '.', 1) NOT IN (
                  'role',          -- role.assign / .create / .update / .delete
                  'user',          -- user.delete / .write / ...
                  'api_key',
                  'rpa',
                  'plugins',       -- covers plugins.admin.* (domain is 'plugins')
                  'secret',
                  'finance',
                  'organisation',
                  'org'
              )
          )
    );
$$;

ALTER FUNCTION "public"."is_org_grantable_permission"("uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."is_org_grantable_permission"("uuid") IS 'May this permission be attached to a role belonging to an organisation? Tests the permission DOMAIN against a reserved-domain deny-list (role, user, api_key, rpa, plugins, secret, finance, organisation, org) plus a short explicit name allowlist. Deliberately does NOT use permissions.is_system, which is inconsistent in this catalog -- role.assign and role.create are is_system = false. Fails closed.';

REVOKE EXECUTE ON FUNCTION "public"."is_org_grantable_permission"("uuid") FROM PUBLIC, "anon";
GRANT EXECUTE ON FUNCTION "public"."is_org_grantable_permission"("uuid") TO "authenticated", "service_role";


CREATE OR REPLACE FUNCTION "public"."enforce_org_role_permission_scope"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_perm TEXT;
BEGIN
    IF EXISTS (
        SELECT 1 FROM public.organisation_roles orl WHERE orl.role_id = NEW.role_id
    ) AND NOT public.is_org_grantable_permission(NEW.permission_id) THEN
        SELECT p.name INTO v_perm FROM public.permissions p WHERE p.id = NEW.permission_id;
        RAISE EXCEPTION
            'Permission "%" cannot be granted to an organisation role (reserved for global roles)',
            COALESCE(v_perm, NEW.permission_id::text);
    END IF;

    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."enforce_org_role_permission_scope"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."enforce_org_role_permission_scope"() IS 'Refuses to attach a non-allowlisted permission to a role that belongs to an organisation. Applies to global admins and service_role too, by design -- it is what makes the global "user" role sitting inside an org admin''s grantable set harmless.';

DROP TRIGGER IF EXISTS "enforce_org_role_permission_scope" ON "public"."role_permissions";
CREATE TRIGGER "enforce_org_role_permission_scope"
    BEFORE INSERT OR UPDATE ON "public"."role_permissions"
    FOR EACH ROW EXECUTE FUNCTION "public"."enforce_org_role_permission_scope"();


-- ============================================================================
-- End of File: 20260801010000_organisation_permissions_and_guards.sql
-- ============================================================================
-- Next Migration: 20260801020000_organisation_rls.sql
-- ============================================================================
