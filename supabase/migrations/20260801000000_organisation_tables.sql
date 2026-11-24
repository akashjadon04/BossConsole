-- ============================================================================
-- BOSS Database Schema: Organisation Tables (multi-tenancy foundation)
-- ============================================================================
-- File: 20260801000000_organisation_tables.sql
-- Description:
--   Introduces organisations as a first-class tenant. Until this migration BOSS
--   was a single global tenant: no table carried an org_id, there was no
--   membership concept, and roles/permissions were globally unique and
--   world-readable. This file creates the 9 tables the feature is built on,
--   enables RLS on all of them, and grants table access. It creates NO policies
--   and NO functions -- those land in the two migrations that follow, because
--   the policies depend on predicate functions that depend on these tables.
--
-- Dependencies:
--   - 20251023000001_extensions_and_types.sql  (pgcrypto in schema "extensions")
--   - 20251023000008_rbac_tables.sql           (public.roles)
--   - auth.users                               (Supabase Auth)
--
-- Tables: 9
--   organisations, organisation_domains, reserved_email_domains,
--   organisation_members, organisation_roles, organisation_requests,
--   organisation_invites, organisation_invite_redemptions,
--   organisation_handoff_tokens
--
-- Next migration: 20260801010000_organisation_permissions_and_guards.sql
-- ============================================================================


-- ============================================================================
-- Architecture overview -- and the three hazards this design is shaped by
-- ============================================================================
--
-- Relationships:
--   organisations (1) <-> (N) organisation_members    -> auth.users
--   organisations (1) <-> (N) organisation_roles      -> public.roles
--   organisations (1) <-> (N) organisation_domains
--   organisations (1) <-> (N) organisation_invites    <-> (N) redemptions
--   organisation_requests (N) -> (1) organisations    (created_org_id, on approval)
--
-- H1 -- authorize() IS ORG-BLIND.
--   public.authorize('organisation.admin') answers "does this user hold the
--   permission ANYWHERE", and it short-circuits true for global admins
--   (20260625000000 SECTION 3). A member holding it via org A would therefore
--   pass a check for org B. NO org-scoped mutation may be gated on authorize().
--   Every org gate is public.is_org_admin(org_id) / is_org_member(org_id),
--   which resolve membership from organisation_members + organisation_roles.
--   'organisation.admin' exists only to be CARRIED by an org admin role so the
--   desktop UI can render an admin affordance; it is never the decision.
--
-- H2 -- SLUG -> ROLE-NAME COLLISION IS A PRIVILEGE ESCALATION.
--   Org roles are named <slug>_admin / <slug>_user. But boss_admin,
--   finance_admin and boss_plugin_admin already exist as GLOBAL SYSTEM roles
--   carrying role.create / role.assign / plugins.admin.*. An org with slug
--   'boss' would derive 'boss_admin' and map that global role into
--   organisation_roles, handing every boss-org admin global powers. Defended in
--   the next migration by a reserved-slug list, a pre-flight "derived name
--   already exists" refusal, and a trigger refusing to map any role with
--   is_system = true. organisation_roles -- NOT the name -- is authoritative,
--   which is why the seeded 'boss' org uses boss_org_admin / boss_org_user.
--
-- H3 -- THE <slug>_user -> user HIERARCHY EDGE WIDENS DELEGATION.
--   get_grantable_role_ids returns strict descendants, so an org admin's
--   grantable set includes the global 'user' role. Defended in the next
--   migration by an allowlist trigger on role_permissions: a role mapped in
--   organisation_roles may only hold permissions OUTSIDE the reserved platform
--   domains (role, user, api_key, rpa, plugins, secret, finance, organisation),
--   plus a short explicit name allowlist. That makes role.* / user.* /
--   api_key.* / plugins.admin.* structurally un-grantable to an org role.
--   The test is the permission's DOMAIN, deliberately NOT permissions.is_system:
--   that flag is inconsistent in this catalog -- role.assign, role.create,
--   role.update and role.delete are all is_system = false, so an
--   "allow anything non-system" rule would have permitted exactly the grant this
--   guard exists to stop. See is_org_grantable_permission in 20260801010000.
--
-- SLUG CHARSET.
--   Slugs are ^[a-z][a-z0-9_]{1,30}$ -- underscores, NO hyphens. Org role names
--   are derived from the slug and public.roles.name is validated
--   ^[a-z][a-z0-9_]{2,50}$ by create_new_role (20260625000000:426), which
--   rejects '-'. Underscore-only keeps the slug -> role-name map total.
--
-- MUTATION PATH.
--   These tables are written ONLY by SECURITY DEFINER RPCs (migrations 4-7).
--   20260801020000_organisation_rls.sql deliberately creates SELECT policies
--   only; the absence of INSERT/UPDATE/DELETE policies is the design, not an
--   oversight.
-- ============================================================================


-- ============================================================================
-- SECTION 1: organisations
-- ============================================================================

CREATE TABLE IF NOT EXISTS "public"."organisations" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "slug" "text" NOT NULL,
    "name" "text" NOT NULL,
    "description" "text",
    "visibility" "text" DEFAULT 'private'::"text" NOT NULL,
    "join_policy" "text" DEFAULT 'invite_only'::"text" NOT NULL,
    "publish_policy" "text" DEFAULT 'admins'::"text" NOT NULL,
    "publish_role_id" "uuid",
    "auto_assign_member_role" boolean DEFAULT true NOT NULL,
    "max_custom_roles" integer DEFAULT 25 NOT NULL,
    "owner_id" "uuid" NOT NULL,
    "is_system" boolean DEFAULT false NOT NULL,
    "created_by" "uuid",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "organisations_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "organisations_slug_key" UNIQUE ("slug"),
    -- Narrower than a URL slug on purpose: no hyphens. See SLUG CHARSET above.
    CONSTRAINT "organisations_slug_format" CHECK ("slug" ~ '^[a-z][a-z0-9_]{1,30}$'),
    CONSTRAINT "organisations_name_length" CHECK ("char_length"("name") BETWEEN 2 AND 100),
    CONSTRAINT "organisations_max_custom_roles_check" CHECK ("max_custom_roles" BETWEEN 0 AND 200),
    CONSTRAINT "organisations_visibility_check"
        CHECK ("visibility" = ANY (ARRAY['private'::"text", 'public'::"text"])),
    CONSTRAINT "organisations_join_policy_check"
        CHECK ("join_policy" = ANY (ARRAY['invite_only'::"text", 'request_to_join'::"text", 'open'::"text"])),
    CONSTRAINT "organisations_publish_policy_check"
        CHECK ("publish_policy" = ANY (ARRAY['owner_only'::"text", 'admins'::"text", 'members'::"text"])),
    -- RESTRICT, not CASCADE: deleting a user must never silently delete an
    -- organisation and, through it, that org's plugins and secrets. Ownership
    -- must be transferred first (transfer_organisation_ownership).
    CONSTRAINT "organisations_owner_fkey" FOREIGN KEY ("owner_id")
        REFERENCES "auth"."users"("id") ON DELETE RESTRICT,
    CONSTRAINT "organisations_created_by_fkey" FOREIGN KEY ("created_by")
        REFERENCES "auth"."users"("id") ON DELETE SET NULL,
    CONSTRAINT "organisations_publish_role_fkey" FOREIGN KEY ("publish_role_id")
        REFERENCES "public"."roles"("id") ON DELETE SET NULL
);

ALTER TABLE "public"."organisations" OWNER TO "postgres";

COMMENT ON TABLE "public"."organisations" IS 'Tenant root. Owns members, roles, plugins, secrets, invite links and registered domains. Written only by SECURITY DEFINER RPCs -- there are deliberately no INSERT/UPDATE/DELETE RLS policies.';

COMMENT ON COLUMN "public"."organisations"."slug" IS 'Immutable short name, ^[a-z][a-z0-9_]{1,30}$. Org role names derive from it, and public.roles.name rejects hyphens -- hence underscores only.';

COMMENT ON COLUMN "public"."organisations"."visibility" IS 'private = absent from discovery unless the caller is a member/applicant or their CONFIRMED email domain matches a VERIFIED org domain. public = listed in discovery for everyone.';

COMMENT ON COLUMN "public"."organisations"."join_policy" IS 'invite_only = no request possible, an invite link or an admin add is the only way in. request_to_join = anyone who can discover it may apply, an org admin approves. open = immediate self-join.';

COMMENT ON COLUMN "public"."organisations"."publish_policy" IS 'Who may publish a plugin owned by this org: owner_only | admins | members. Overridden entirely by publish_role_id when that is set. Evaluated by user_can_publish_org_plugin() -- never re-implemented in TypeScript.';

COMMENT ON COLUMN "public"."organisations"."publish_role_id" IS 'When set, publishing requires holding THIS role and publish_policy is ignored. Lets an org delegate publishing to a custom role without making those members admins.';

COMMENT ON COLUMN "public"."organisations"."auto_assign_member_role" IS 'Whether joining assigns the org user-kind role. False for the seeded boss org: every user is a member, so assigning it would add a user_roles row per user and lengthen every JWT for zero extra permissions (the global "user" role already carries the baseline).';

COMMENT ON COLUMN "public"."organisations"."owner_id" IS 'Sole transferable owner. ON DELETE RESTRICT -- deleting this user fails until ownership is transferred, which is deliberate: an org must not be collectible damage from a user deletion.';

COMMENT ON COLUMN "public"."organisations"."is_system" IS 'True only for the seeded boss org. Bypasses the reserved-slug check at creation and marks the org as undeletable.';

COMMENT ON COLUMN "public"."organisations"."updated_at" IS 'Maintained by the RPCs (SET updated_at = now()), matching update_secret. This schema has no updated_at trigger convention.';

CREATE INDEX IF NOT EXISTS "idx_organisations_owner"
    ON "public"."organisations" ("owner_id");

CREATE INDEX IF NOT EXISTS "idx_organisations_visibility"
    ON "public"."organisations" ("visibility") WHERE "visibility" = 'public';

-- Backs the discovery full-text predicate in search_organisations().
CREATE INDEX IF NOT EXISTS "idx_organisations_search"
    ON "public"."organisations"
    USING "gin" ("to_tsvector"('english'::"regconfig", "name" || ' ' || COALESCE("description", '')));


-- ============================================================================
-- SECTION 2: organisation_domains
-- ============================================================================
-- Registered email domains. Only verified = true rows are ever honoured, for
-- discovery or for the domain-based join action. Verification is out-of-band
-- (a DNS TXT record at _boss-verify.<domain>) and the flag is flipped only by
-- mark_organisation_domain_verified(), which is service_role-only and called by
-- the `organisation` edge function. There is deliberately no client-callable
-- path that sets verified = true.

CREATE TABLE IF NOT EXISTS "public"."organisation_domains" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "org_id" "uuid" NOT NULL,
    "domain" "text" NOT NULL,
    "is_primary" boolean DEFAULT false NOT NULL,
    "verified" boolean DEFAULT false NOT NULL,
    "verification_token" "text" NOT NULL,
    "verified_at" timestamp with time zone,
    "verified_by" "uuid",
    "created_by" "uuid",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "organisation_domains_pkey" PRIMARY KEY ("id"),
    -- Globally unique: two organisations cannot both claim acme.com. That is
    -- precisely what makes domain-based discovery and auto-join unambiguous.
    CONSTRAINT "organisation_domains_domain_key" UNIQUE ("domain"),
    CONSTRAINT "organisation_domains_format"
        CHECK ("domain" ~ '^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+$'),
    CONSTRAINT "organisation_domains_verified_shape"
        CHECK (("verified" = false AND "verified_at" IS NULL)
            OR ("verified" = true AND "verified_at" IS NOT NULL)),
    CONSTRAINT "organisation_domains_org_fkey" FOREIGN KEY ("org_id")
        REFERENCES "public"."organisations"("id") ON DELETE CASCADE,
    CONSTRAINT "organisation_domains_verified_by_fkey" FOREIGN KEY ("verified_by")
        REFERENCES "auth"."users"("id") ON DELETE SET NULL,
    CONSTRAINT "organisation_domains_created_by_fkey" FOREIGN KEY ("created_by")
        REFERENCES "auth"."users"("id") ON DELETE SET NULL
);

ALTER TABLE "public"."organisation_domains" OWNER TO "postgres";

COMMENT ON TABLE "public"."organisation_domains" IS 'Email domains claimed by an organisation. Only verified rows are honoured. domain is globally UNIQUE so domain-based discovery has exactly one answer.';

COMMENT ON COLUMN "public"."organisation_domains"."verification_token" IS 'Expected value of the DNS TXT record at _boss-verify.<domain>, as "boss-org-verification=<token>". Low-value on its own -- it only proves DNS control -- but it is revoked from authenticated at the column level and returned only by the org-admin RPC.';

COMMENT ON COLUMN "public"."organisation_domains"."verified" IS 'Flipped ONLY by mark_organisation_domain_verified() (service_role). Never settable by a client.';

CREATE UNIQUE INDEX IF NOT EXISTS "idx_organisation_domains_one_primary"
    ON "public"."organisation_domains" ("org_id") WHERE "is_primary";

CREATE INDEX IF NOT EXISTS "idx_organisation_domains_verified"
    ON "public"."organisation_domains" ("domain") WHERE "verified";

CREATE INDEX IF NOT EXISTS "idx_organisation_domains_org"
    ON "public"."organisation_domains" ("org_id");


-- ============================================================================
-- SECTION 3: reserved_email_domains
-- ============================================================================
-- Without this table any user could register gmail.com as an org domain and
-- auto-absorb every consumer-mailbox signup, past and future. Kept as data
-- rather than a hardcoded list so it is editable without a migration.

CREATE TABLE IF NOT EXISTS "public"."reserved_email_domains" (
    "domain" "text" NOT NULL,
    "reason" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "reserved_email_domains_pkey" PRIMARY KEY ("domain")
);

ALTER TABLE "public"."reserved_email_domains" OWNER TO "postgres";

COMMENT ON TABLE "public"."reserved_email_domains" IS 'Email domains that may never be claimed by an organisation, nor honoured for domain-based discovery or join. Consumer mailboxes plus RFC 2606 reserved names.';

INSERT INTO "public"."reserved_email_domains" ("domain", "reason") VALUES
    ('gmail.com',       'consumer mailbox'),
    ('googlemail.com',  'consumer mailbox'),
    ('outlook.com',     'consumer mailbox'),
    ('hotmail.com',     'consumer mailbox'),
    ('live.com',        'consumer mailbox'),
    ('msn.com',         'consumer mailbox'),
    ('yahoo.com',       'consumer mailbox'),
    ('ymail.com',       'consumer mailbox'),
    ('icloud.com',      'consumer mailbox'),
    ('me.com',          'consumer mailbox'),
    ('mac.com',         'consumer mailbox'),
    ('aol.com',         'consumer mailbox'),
    ('proton.me',       'consumer mailbox'),
    ('protonmail.com',  'consumer mailbox'),
    ('pm.me',           'consumer mailbox'),
    ('gmx.com',         'consumer mailbox'),
    ('gmx.de',          'consumer mailbox'),
    ('mail.com',        'consumer mailbox'),
    ('mail.ru',         'consumer mailbox'),
    ('yandex.com',      'consumer mailbox'),
    ('yandex.ru',       'consumer mailbox'),
    ('zoho.com',        'consumer mailbox'),
    ('fastmail.com',    'consumer mailbox'),
    ('hey.com',         'consumer mailbox'),
    ('duck.com',        'forwarding service'),
    ('example.com',     'reserved (RFC 2606)'),
    ('example.net',     'reserved (RFC 2606)'),
    ('example.org',     'reserved (RFC 2606)'),
    ('invalid.test',    'reserved (RFC 2606)'),
    ('localhost.local', 'reserved (RFC 2606)')
ON CONFLICT ("domain") DO NOTHING;


-- ============================================================================
-- SECTION 4: organisation_members
-- ============================================================================

CREATE TABLE IF NOT EXISTS "public"."organisation_members" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "org_id" "uuid" NOT NULL,
    "user_id" "uuid" NOT NULL,
    "status" "text" DEFAULT 'active'::"text" NOT NULL,
    "joined_at" timestamp with time zone,
    "requested_at" timestamp with time zone,
    "request_message" "text",
    "invited_by" "uuid",
    "invited_at" timestamp with time zone,
    "approved_by" "uuid",
    "approved_at" timestamp with time zone,
    "join_source" "text",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "organisation_members_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "organisation_members_unique" UNIQUE ("org_id", "user_id"),
    CONSTRAINT "organisation_members_status_check"
        CHECK ("status" = ANY (ARRAY['active'::"text", 'pending'::"text", 'invited'::"text"])),
    CONSTRAINT "organisation_members_source_check"
        CHECK ("join_source" IS NULL OR "join_source" = ANY (ARRAY[
            'seed'::"text", 'open'::"text", 'request'::"text",
            'invite'::"text", 'domain'::"text", 'admin'::"text", 'founder'::"text"])),
    CONSTRAINT "organisation_members_active_has_joined_at"
        CHECK ("status" <> 'active' OR "joined_at" IS NOT NULL),
    CONSTRAINT "organisation_members_org_fkey" FOREIGN KEY ("org_id")
        REFERENCES "public"."organisations"("id") ON DELETE CASCADE,
    CONSTRAINT "organisation_members_user_fkey" FOREIGN KEY ("user_id")
        REFERENCES "auth"."users"("id") ON DELETE CASCADE,
    CONSTRAINT "organisation_members_invited_by_fkey" FOREIGN KEY ("invited_by")
        REFERENCES "auth"."users"("id") ON DELETE SET NULL,
    CONSTRAINT "organisation_members_approved_by_fkey" FOREIGN KEY ("approved_by")
        REFERENCES "auth"."users"("id") ON DELETE SET NULL
);

ALTER TABLE "public"."organisation_members" OWNER TO "postgres";

COMMENT ON TABLE "public"."organisation_members" IS 'User <-> organisation membership. status active = a member; pending = applied and awaiting org-admin approval; invited = added by an admin, not yet accepted. Removing a member must also delete their user_roles rows for this org (remove_organisation_member does) or they keep org permissions.';

COMMENT ON COLUMN "public"."organisation_members"."join_source" IS 'How they got in: seed (boss-org backfill) | open (self-join) | request (approved application) | invite (link) | domain (verified email domain) | admin (added) | founder (created the org).';

CREATE INDEX IF NOT EXISTS "idx_organisation_members_user"
    ON "public"."organisation_members" ("user_id");

CREATE INDEX IF NOT EXISTS "idx_organisation_members_org"
    ON "public"."organisation_members" ("org_id");

-- THE hot index. Every is_org_member()/is_org_admin() call -- and therefore
-- every org RLS check on organisations, secrets and plugins -- probes this.
CREATE INDEX IF NOT EXISTS "idx_organisation_members_org_active"
    ON "public"."organisation_members" ("org_id", "user_id") WHERE "status" = 'active';

CREATE INDEX IF NOT EXISTS "idx_organisation_members_pending"
    ON "public"."organisation_members" ("org_id") WHERE "status" = 'pending';


-- ============================================================================
-- SECTION 5: organisation_roles
-- ============================================================================
-- The mapping that makes an ordinary row in public.roles an ORG role. This
-- table -- not the role's name -- is authoritative (H2). Two triggers in the
-- next migration hang off it and off role_permissions.

CREATE TABLE IF NOT EXISTS "public"."organisation_roles" (
    "org_id" "uuid" NOT NULL,
    "role_id" "uuid" NOT NULL,
    "kind" "text" NOT NULL,
    "created_by" "uuid",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "organisation_roles_pkey" PRIMARY KEY ("org_id", "role_id"),
    -- A role belongs to AT MOST ONE organisation. Without this a single role
    -- could be mapped into two orgs and org A's admins would then govern org
    -- B's members through it.
    CONSTRAINT "organisation_roles_role_key" UNIQUE ("role_id"),
    CONSTRAINT "organisation_roles_kind_check"
        CHECK ("kind" = ANY (ARRAY['admin'::"text", 'user'::"text", 'custom'::"text"])),
    CONSTRAINT "organisation_roles_org_fkey" FOREIGN KEY ("org_id")
        REFERENCES "public"."organisations"("id") ON DELETE CASCADE,
    CONSTRAINT "organisation_roles_role_fkey" FOREIGN KEY ("role_id")
        REFERENCES "public"."roles"("id") ON DELETE CASCADE,
    CONSTRAINT "organisation_roles_created_by_fkey" FOREIGN KEY ("created_by")
        REFERENCES "auth"."users"("id") ON DELETE SET NULL
);

ALTER TABLE "public"."organisation_roles" OWNER TO "postgres";

COMMENT ON TABLE "public"."organisation_roles" IS 'Maps rows in public.roles to an organisation. AUTHORITATIVE -- org membership of a role is decided here, never by parsing the role name, which is why the seeded boss org can use boss_org_admin rather than the colliding global boss_admin.';

COMMENT ON COLUMN "public"."organisation_roles"."kind" IS 'admin = the org administrator role (is_org_admin resolves through it); user = the default member role; custom = an extra role created by an org admin, always <slug>_ prefixed and slotted between the admin and user roles in the hierarchy.';

-- Exactly one admin-kind and one user-kind role per organisation.
CREATE UNIQUE INDEX IF NOT EXISTS "idx_organisation_roles_one_per_kind"
    ON "public"."organisation_roles" ("org_id", "kind") WHERE "kind" IN ('admin', 'user');

CREATE INDEX IF NOT EXISTS "idx_organisation_roles_org"
    ON "public"."organisation_roles" ("org_id");


-- ============================================================================
-- SECTION 6: organisation_requests
-- ============================================================================
-- Any authenticated user holding 'organisation.create' submits one; a global
-- Boss admin holding 'organisation.approve' reviews it. Approval calls
-- create_organisation_internal in the same transaction, so the org either
-- materialises completely or the request stays pending.

CREATE TABLE IF NOT EXISTS "public"."organisation_requests" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "requester_id" "uuid" NOT NULL,
    "name" "text" NOT NULL,
    "slug" "text" NOT NULL,
    "description" "text",
    "domain" "text",
    "justification" "text",
    "status" "text" DEFAULT 'pending'::"text" NOT NULL,
    "reviewer_id" "uuid",
    "review_notes" "text",
    "reviewed_at" timestamp with time zone,
    "created_org_id" "uuid",
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    "updated_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "organisation_requests_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "organisation_requests_slug_format" CHECK ("slug" ~ '^[a-z][a-z0-9_]{1,30}$'),
    CONSTRAINT "organisation_requests_name_length" CHECK ("char_length"("name") BETWEEN 2 AND 100),
    CONSTRAINT "organisation_requests_status_check"
        CHECK ("status" = ANY (ARRAY['pending'::"text", 'approved'::"text", 'rejected'::"text", 'withdrawn'::"text"])),
    CONSTRAINT "organisation_requests_reviewed_shape" CHECK (
        ("status" = 'pending' AND "reviewer_id" IS NULL AND "reviewed_at" IS NULL)
        OR ("status" = 'withdrawn')
        OR ("status" = ANY (ARRAY['approved'::"text", 'rejected'::"text"])
            AND "reviewer_id" IS NOT NULL AND "reviewed_at" IS NOT NULL)),
    CONSTRAINT "organisation_requests_approved_has_org"
        CHECK ("status" <> 'approved' OR "created_org_id" IS NOT NULL),
    CONSTRAINT "organisation_requests_requester_fkey" FOREIGN KEY ("requester_id")
        REFERENCES "auth"."users"("id") ON DELETE CASCADE,
    CONSTRAINT "organisation_requests_reviewer_fkey" FOREIGN KEY ("reviewer_id")
        REFERENCES "auth"."users"("id") ON DELETE SET NULL,
    CONSTRAINT "organisation_requests_org_fkey" FOREIGN KEY ("created_org_id")
        REFERENCES "public"."organisations"("id") ON DELETE SET NULL
);

ALTER TABLE "public"."organisation_requests" OWNER TO "postgres";

COMMENT ON TABLE "public"."organisation_requests" IS 'Self-service organisation creation requests, reviewed by holders of organisation.approve. This is the one place a bare authorize() check is correct, because organisation.approve is a genuinely global permission.';

-- Only ONE live request per slug -- two people cannot race the same name.
-- Approved orgs are protected separately by organisations_slug_key.
CREATE UNIQUE INDEX IF NOT EXISTS "idx_organisation_requests_pending_slug"
    ON "public"."organisation_requests" ("slug") WHERE "status" = 'pending';

CREATE INDEX IF NOT EXISTS "idx_organisation_requests_status"
    ON "public"."organisation_requests" ("status", "created_at" DESC);

CREATE INDEX IF NOT EXISTS "idx_organisation_requests_requester"
    ON "public"."organisation_requests" ("requester_id");


-- ============================================================================
-- SECTION 7: organisation_invites + organisation_invite_redemptions
-- ============================================================================

CREATE TABLE IF NOT EXISTS "public"."organisation_invites" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "org_id" "uuid" NOT NULL,
    "token_hash" "text" NOT NULL,
    "token_prefix" "text" NOT NULL,
    "role_id" "uuid",
    "label" "text",
    "max_uses" integer,
    "uses" integer DEFAULT 0 NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "revoked_at" timestamp with time zone,
    "revoked_by" "uuid",
    "created_by" "uuid" NOT NULL,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "organisation_invites_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "organisation_invites_token_hash_key" UNIQUE ("token_hash"),
    CONSTRAINT "organisation_invites_max_uses_check" CHECK ("max_uses" IS NULL OR "max_uses" > 0),
    CONSTRAINT "organisation_invites_uses_check" CHECK ("uses" >= 0),
    CONSTRAINT "organisation_invites_org_fkey" FOREIGN KEY ("org_id")
        REFERENCES "public"."organisations"("id") ON DELETE CASCADE,
    CONSTRAINT "organisation_invites_role_fkey" FOREIGN KEY ("role_id")
        REFERENCES "public"."roles"("id") ON DELETE SET NULL,
    CONSTRAINT "organisation_invites_creator_fkey" FOREIGN KEY ("created_by")
        REFERENCES "auth"."users"("id") ON DELETE CASCADE,
    CONSTRAINT "organisation_invites_revoked_by_fkey" FOREIGN KEY ("revoked_by")
        REFERENCES "auth"."users"("id") ON DELETE SET NULL
);

ALTER TABLE "public"."organisation_invites" OWNER TO "postgres";

COMMENT ON TABLE "public"."organisation_invites" IS 'Token-based join links. Only the SHA-256 hash is stored (same posture as plugin_api_keys.key_hash) -- the plaintext exists exactly once, in create_organisation_invite''s return value.';

COMMENT ON COLUMN "public"."organisation_invites"."expires_at" IS 'NOT NULL by design: an invite link with no expiry is a permanent organisation backdoor. Capped at 30 days by create_organisation_invite.';

COMMENT ON COLUMN "public"."organisation_invites"."role_id" IS 'Optional org role granted on redemption. create_organisation_invite refuses an admin-kind role -- a link that grants admin is an org-takeover primitive if the URL leaks, so admin promotion needs an explicit assign_organisation_role call.';

COMMENT ON COLUMN "public"."organisation_invites"."token_prefix" IS 'First characters of the token, for display in the admin UI only. Never sufficient to redeem.';

CREATE INDEX IF NOT EXISTS "idx_organisation_invites_org"
    ON "public"."organisation_invites" ("org_id");

CREATE INDEX IF NOT EXISTS "idx_organisation_invites_live"
    ON "public"."organisation_invites" ("org_id") WHERE "revoked_at" IS NULL;


CREATE TABLE IF NOT EXISTS "public"."organisation_invite_redemptions" (
    "invite_id" "uuid" NOT NULL,
    "user_id" "uuid" NOT NULL,
    "redeemed_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "organisation_invite_redemptions_pkey" PRIMARY KEY ("invite_id", "user_id"),
    CONSTRAINT "organisation_invite_redemptions_invite_fkey" FOREIGN KEY ("invite_id")
        REFERENCES "public"."organisation_invites"("id") ON DELETE CASCADE,
    CONSTRAINT "organisation_invite_redemptions_user_fkey" FOREIGN KEY ("user_id")
        REFERENCES "auth"."users"("id") ON DELETE CASCADE
);

ALTER TABLE "public"."organisation_invite_redemptions" OWNER TO "postgres";

COMMENT ON TABLE "public"."organisation_invite_redemptions" IS 'One row per (invite, user). Makes a re-click by the same person idempotent instead of burning another use against max_uses.';


-- ============================================================================
-- SECTION 8: organisation_handoff_tokens
-- ============================================================================
-- The desktop plugin cannot hand its Supabase JWT to a web page, so it mints a
-- short-lived single-use token, opens
--   <functions>/organisation/o/<slug>?t=<token>
-- and the `organisation` edge function exchanges it (service_role) for the
-- user's identity, sets its own signed cookie, and redirects to strip the token
-- from the address bar and history.
--
-- Only the SHA-256 hash is stored. The token is URL-safe, ~5 minutes long and
-- single-use, but it IS a bearer credential -- the consuming page must never
-- echo it and must never log it.

CREATE TABLE IF NOT EXISTS "public"."organisation_handoff_tokens" (
    "id" "uuid" DEFAULT "gen_random_uuid"() NOT NULL,
    "token_hash" "text" NOT NULL,
    "user_id" "uuid" NOT NULL,
    "org_id" "uuid",
    "purpose" "text" DEFAULT 'org_view'::"text" NOT NULL,
    "expires_at" timestamp with time zone NOT NULL,
    "consumed_at" timestamp with time zone,
    "created_at" timestamp with time zone DEFAULT "now"() NOT NULL,
    CONSTRAINT "organisation_handoff_tokens_pkey" PRIMARY KEY ("id"),
    CONSTRAINT "organisation_handoff_tokens_token_hash_key" UNIQUE ("token_hash"),
    CONSTRAINT "organisation_handoff_tokens_purpose_check"
        CHECK ("purpose" ~ '^[a-z][a-z0-9_]{1,30}$'),
    CONSTRAINT "organisation_handoff_tokens_user_fkey" FOREIGN KEY ("user_id")
        REFERENCES "auth"."users"("id") ON DELETE CASCADE,
    CONSTRAINT "organisation_handoff_tokens_org_fkey" FOREIGN KEY ("org_id")
        REFERENCES "public"."organisations"("id") ON DELETE CASCADE
);

ALTER TABLE "public"."organisation_handoff_tokens" OWNER TO "postgres";

COMMENT ON TABLE "public"."organisation_handoff_tokens" IS 'Short-lived single-use tokens that hand a desktop session to the organisation edge function''s web pages. Minted by the authenticated user for THEMSELVES only (mint has no p_user_id parameter -- that is the security property); consumed only by service_role.';

COMMENT ON COLUMN "public"."organisation_handoff_tokens"."purpose" IS 'Which page the token is good for, e.g. org_view or org_admin. Informational ONLY. It is carried into the session cookie as `pur`, but it is not an authority: the admin page gates on a live user_is_org_admin probe alone, so an org_view handoff reaches /admin whenever the user genuinely is an admin. Do not treat it as a second gate -- session.ts is explicit that pur carries none.';

COMMENT ON COLUMN "public"."organisation_handoff_tokens"."consumed_at" IS 'Set by the single atomic UPDATE ... WHERE consumed_at IS NULL RETURNING inside consume_organisation_handoff_token. That statement IS the single-use primitive -- no read-then-write race, no advisory lock.';

CREATE INDEX IF NOT EXISTS "idx_organisation_handoff_tokens_expires"
    ON "public"."organisation_handoff_tokens" ("expires_at");

CREATE INDEX IF NOT EXISTS "idx_organisation_handoff_tokens_user"
    ON "public"."organisation_handoff_tokens" ("user_id");


-- Probabilistic cleanup on insert. Consumed/expired rows are kept for a day so
-- there is a short audit trail and replay attempts stay observable.
CREATE OR REPLACE FUNCTION "public"."trigger_cleanup_expired_handoff_tokens"() RETURNS "trigger"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
BEGIN
    IF "random"() < 0.1 THEN
        DELETE FROM public.organisation_handoff_tokens
        WHERE expires_at < now() - interval '1 day';
    END IF;
    RETURN NEW;
END;
$$;

ALTER FUNCTION "public"."trigger_cleanup_expired_handoff_tokens"() OWNER TO "postgres";

COMMENT ON FUNCTION "public"."trigger_cleanup_expired_handoff_tokens"() IS 'Deletes handoff tokens more than a day past expiry, on roughly 10% of inserts. Avoids needing a scheduled job for a table that only ever holds short-lived rows.';

DROP TRIGGER IF EXISTS "trigger_cleanup_expired_handoff_tokens_on_insert"
    ON "public"."organisation_handoff_tokens";
CREATE TRIGGER "trigger_cleanup_expired_handoff_tokens_on_insert"
    AFTER INSERT ON "public"."organisation_handoff_tokens"
    FOR EACH ROW EXECUTE FUNCTION "public"."trigger_cleanup_expired_handoff_tokens"();


-- ============================================================================
-- SECTION 9: Enable RLS
-- ============================================================================
-- Policies land in 20260801020000_organisation_rls.sql, which needs the
-- predicate functions from 20260801010000. Until that migration runs these
-- tables are RLS-enabled with zero permissive policies, i.e. deny-all for
-- anon/authenticated -- fail closed, which is the correct intermediate state.

ALTER TABLE "public"."organisations"                   ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."organisation_domains"            ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."reserved_email_domains"          ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."organisation_members"            ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."organisation_roles"              ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."organisation_requests"           ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."organisation_invites"            ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."organisation_invite_redemptions" ENABLE ROW LEVEL SECURITY;
ALTER TABLE "public"."organisation_handoff_tokens"     ENABLE ROW LEVEL SECURITY;


-- ============================================================================
-- SECTION 10: Table grants
-- ============================================================================
-- Deliberately TIGHTER than 20251023000014_grants.sql, which hands
-- GRANT ALL to anon and authenticated on every table and leans entirely on RLS.
-- Here: authenticated gets SELECT only (all writes go through SECURITY DEFINER
-- RPCs), anon gets nothing at all, and service_role gets everything.
--
-- organisation_handoff_tokens and organisation_invites are excluded from the
-- authenticated SELECT grant on purpose: their token_hash columns must never be
-- readable by a client. Invites are surfaced by list_organisation_invites,
-- which projects token_prefix and never token_hash.

-- REVOKE FIRST. Supabase's 20251023000014_grants.sql sets
-- `ALTER DEFAULT PRIVILEGES ... GRANT ALL ON TABLES TO anon, authenticated` for schema public,
-- so every CREATE TABLE above already handed BOTH roles ALL privileges - this file never granted
-- them and they are there anyway. Without these revokes the SELECT grants below are additive
-- noise, `anon` holds ALL on nine organisation tables, and the writes are held off by RLS alone
-- rather than by RLS *and* the absence of a grant.
--
-- organisation_invites and organisation_handoff_tokens deliberately get NOTHING back: the invite
-- table holds token_hash and the handoff table holds live credential hashes, and both are only
-- ever reached through SECURITY DEFINER RPCs.
REVOKE ALL ON TABLE "public"."organisations"                   FROM "anon", "authenticated";
REVOKE ALL ON TABLE "public"."organisation_domains"            FROM "anon", "authenticated";
REVOKE ALL ON TABLE "public"."organisation_members"            FROM "anon", "authenticated";
REVOKE ALL ON TABLE "public"."organisation_roles"              FROM "anon", "authenticated";
REVOKE ALL ON TABLE "public"."organisation_requests"           FROM "anon", "authenticated";
REVOKE ALL ON TABLE "public"."organisation_invites"            FROM "anon", "authenticated";
REVOKE ALL ON TABLE "public"."organisation_invite_redemptions" FROM "anon", "authenticated";
REVOKE ALL ON TABLE "public"."organisation_handoff_tokens"     FROM "anon", "authenticated";
REVOKE ALL ON TABLE "public"."reserved_email_domains"          FROM "anon", "authenticated";

GRANT SELECT ON TABLE "public"."organisations"                   TO "authenticated";
-- Column-level, deliberately: NOT the verification token.
--
-- The SELECT policy on this table is `is_org_member(org_id) OR verified`, so a
-- verified row is deliberately readable by any authenticated user - verified
-- domains are discovery metadata. RLS is row-level, though, so the token rode
-- along on every one of those rows, and two comments in this batch claimed it
-- "is revoked from authenticated at the column level and returned only by the org-admin RPC". It was not.
--
-- A column-level revoke keeps the row discoverable and the token private, which
-- is the actual intent, without splitting the table. list_organisation_domains
-- is SECURITY DEFINER and admin-gated, so the admin path is unaffected.
-- Two things are needed, and missing either one makes this silently a no-op.
--
-- 1. REVOKE the table-level grant first. Supabase sets DEFAULT PRIVILEGES on the
--    public schema (see pg_default_acl), so every CREATE TABLE here hands
--    authenticated full table-level SELECT automatically - this migration never
--    granted it explicitly and it is there anyway.
-- 2. A column-level REVOKE cannot carve a column out of a table-level grant; the
--    table grant still wins. So the grant has to be re-issued column by column.
--
-- Verified by test rather than by reading: the first attempt did only step 2 and
-- passed when applied by hand to an already-migrated database, then failed on a
-- fresh `db reset` where the default ACL had just re-granted the table.
REVOKE SELECT ON TABLE "public"."organisation_domains" FROM "authenticated";

GRANT SELECT (
    "id", "org_id", "domain", "is_primary", "verified", "verified_at",
    "verified_by", "created_by", "created_at"
) ON TABLE "public"."organisation_domains" TO "authenticated";
GRANT SELECT ON TABLE "public"."reserved_email_domains"          TO "authenticated";
GRANT SELECT ON TABLE "public"."organisation_members"            TO "authenticated";
GRANT SELECT ON TABLE "public"."organisation_roles"              TO "authenticated";
GRANT SELECT ON TABLE "public"."organisation_requests"           TO "authenticated";
GRANT SELECT ON TABLE "public"."organisation_invite_redemptions" TO "authenticated";

GRANT ALL ON TABLE "public"."organisations"                   TO "service_role";
GRANT ALL ON TABLE "public"."organisation_domains"            TO "service_role";
GRANT ALL ON TABLE "public"."reserved_email_domains"          TO "service_role";
GRANT ALL ON TABLE "public"."organisation_members"            TO "service_role";
GRANT ALL ON TABLE "public"."organisation_roles"              TO "service_role";
GRANT ALL ON TABLE "public"."organisation_requests"           TO "service_role";
GRANT ALL ON TABLE "public"."organisation_invites"            TO "service_role";
GRANT ALL ON TABLE "public"."organisation_invite_redemptions" TO "service_role";
GRANT ALL ON TABLE "public"."organisation_handoff_tokens"     TO "service_role";


-- ============================================================================
-- End of File: 20260801000000_organisation_tables.sql
-- ============================================================================
-- Next Migration: 20260801010000_organisation_permissions_and_guards.sql
-- ============================================================================
