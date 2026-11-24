-- ============================================================================
-- BOSS Database Schema: the Secret Manager becomes a per-user vault
-- ============================================================================
-- File: 20260809000000_secret_read_for_user_role.sql
-- Description:
--   Grants `secret.read` to the baseline `user` role, so every authenticated
--   user can open the Secret Manager panel and Settings > AI Providers.
--
--   WHY THIS IS A CORRECTION, NOT A WIDENING OF ACCESS.
--   20260628000000 created `secret.read` and granted it to admin + boss_admin.
--   Read its header: the panel "was historically gated by the legacy
--   `requiresAdmin` flag (admin role only)", and that migration existed to let
--   boss_admin in. It translated the old admin-only behaviour forward verbatim.
--   Nobody ever decided a normal user should not have a vault -- and the data
--   layer says the opposite at every level:
--
--     * every secret RPC is granted TO "authenticated" with no permission check
--       (20260802000000, SECTION "GRANTS"),
--     * every one of them self-scopes with auth.uid()
--       (create_secret, delete_secret, get_user_secrets, search_user_secrets),
--     * all four RLS policies on public.secrets are auth.uid() = user_id
--       (20251023000013).
--
--   So `secret.read` is a client-side visibility gate over a store that is
--   already per-user. This migration widens server-side access by zero rows.
--
--   WHAT IT ACTUALLY UNBLOCKS. Since the AI provider settings moved into the
--   secret-manager plugin, that plugin owns Settings > AI Providers and the
--   LlmProviderSettingsAPI behind PluginContext.llmProvider. DynamicPluginManager
--   skips register() entirely for a plugin the user cannot access, so a non-admin
--   got no AI provider settings, no way to add their own key, and a null
--   llmProvider in every other plugin. All AI in BOSS was admin-only by accident.
--
--   THE TIGHTENING THAT COMES WITH IT. `share_secret` gated role targets on
--   can_manage_secret alone -- i.e. any owner could share a secret with any
--   global role, including `user`, which is a descendant of everything and
--   therefore means "everyone" (20260802010000 states this). That was survivable
--   only because non-admins could not reach the panel. It is not survivable
--   afterwards, so role targets now require a new `secret.share.role`
--   permission, granted to admin + boss_admin. User and organisation targets are
--   unchanged.
--
--   NOT CHANGED HERE, deliberately:
--     * the explicit admin/boss_admin grants of `secret.read` stay. They are
--       redundant once `user` holds it (both are ancestors of `user`), but
--       removing a grant is destructive and buys nothing.
--     * `secret.read` stays is_system and stays on the org-grantable allowlist.
--       Its meaning shifts from "who may use the vault" to "who may still use
--       the vault" -- a deployment that wants it locked down revokes it from
--       `user`, which is a supported operation.
--
-- Dependencies:
--   - 20260628000000_secret_read_permission.sql  (creates secret.read)
--   - 20260625000000_role_hierarchy_and_granular_rbac.sql  (the `user` role)
--   - 20260802000000_secrets_org_ownership.sql   (the share_secret recreated here)
--   - 20260801010000_organisation_permissions_and_guards.sql (is_org_grantable_permission)
--
-- CREATE OR REPLACE is correct for share_secret: the argument list and the jsonb
-- return type are both unchanged, so no second overload is created and the
-- existing GRANTs survive on the preserved function OID. (A signature change
-- would need the DROP-then-recreate dance -- see 20260802000000 hazard 1.)
--
-- All seed data is idempotent (ON CONFLICT DO NOTHING); safe to re-run.
-- ============================================================================


-- ============================================================================
-- SECTION 1: secret.read for the baseline role
-- ============================================================================

INSERT INTO "public"."role_permissions" ("role_id", "permission_id")
SELECT r."id", p."id"
FROM "public"."roles" r
JOIN "public"."permissions" p ON p."name" = 'secret.read'
WHERE r."name" = 'user'
ON CONFLICT ("role_id", "permission_id") DO NOTHING;


-- ============================================================================
-- SECTION 2: secret.share.role, and the gate that uses it
-- ============================================================================

-- is_system so delete_permission() refuses to drop it. Domain `secret` is on the
-- reserved deny-list in is_org_grantable_permission, and this name is NOT on that
-- function's short allowlist (only `secret.read` is) -- so an organisation admin
-- cannot attach it to an org role and mint a global-role sharer. That is intended:
-- an org admin's sharing power is over their own organisation, which the
-- p_target_org_id branch already serves.
INSERT INTO "public"."permissions" ("name", "description", "is_system")
VALUES ('secret.share.role', 'Share a secret with an RBAC role (fans out to every holder)', true)
ON CONFLICT ("name") DO NOTHING;

INSERT INTO "public"."role_permissions" ("role_id", "permission_id")
SELECT r."id", p."id"
FROM (VALUES ('admin'), ('boss_admin')) AS grant_map("role_name")
JOIN "public"."roles" r ON r."name" = grant_map."role_name"
JOIN "public"."permissions" p ON p."name" = 'secret.share.role'
ON CONFLICT ("role_id", "permission_id") DO NOTHING;


CREATE OR REPLACE FUNCTION "public"."share_secret"(
    "p_secret_id" "uuid",
    "p_target_user_id" "uuid" DEFAULT NULL::"uuid",
    "p_target_role_id" "uuid" DEFAULT NULL::"uuid",
    "p_notes" "text" DEFAULT NULL::"text",
    "p_expires_at" timestamp with time zone DEFAULT NULL::timestamp with time zone,
    "p_target_org_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_is_owner BOOLEAN;
    v_is_org_admin BOOLEAN;
    v_target_email TEXT;
    v_target_role_name TEXT;
    v_target_org_slug TEXT;
    v_secret_website TEXT;
    v_secret_org UUID;
    v_target_count INTEGER;
    v_via TEXT;
BEGIN
    IF auth.uid() IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    -- Was an inline "owner OR literal admin role" check. can_manage_secret keeps
    -- both of those and additionally lets an admin of the OWNING organisation
    -- share an organisation secret -- which is the point of org ownership.
    IF NOT public.can_manage_secret(p_secret_id) THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Unauthorized: You must be the owner, an organisation administrator, or an admin to share this secret');
    END IF;

    SELECT s.website, s.user_id = auth.uid(), s.org_id
      INTO v_secret_website, v_is_owner, v_secret_org
      FROM public.secrets s WHERE s.id = p_secret_id;

    IF v_secret_website IS NULL AND NOT FOUND THEN
        RETURN jsonb_build_object('success', false, 'error', 'Secret not found');
    END IF;

    v_is_org_admin := v_secret_org IS NOT NULL AND public.is_org_admin(v_secret_org);

    -- Exactly one of three targets. share_target_check would catch this at insert
    -- time, but a clear message beats a constraint violation.
    v_target_count := (CASE WHEN p_target_user_id IS NOT NULL THEN 1 ELSE 0 END)
                    + (CASE WHEN p_target_role_id IS NOT NULL THEN 1 ELSE 0 END)
                    + (CASE WHEN p_target_org_id  IS NOT NULL THEN 1 ELSE 0 END);
    IF v_target_count <> 1 THEN
        RETURN jsonb_build_object('success', false, 'error',
            'Must specify exactly one of target_user_id, target_role_id or target_org_id');
    END IF;

    IF p_target_user_id IS NOT NULL THEN
        SELECT u.email INTO v_target_email FROM auth.users u WHERE u.id = p_target_user_id;
        IF v_target_email IS NULL THEN
            RETURN jsonb_build_object('success', false, 'error', 'User not found');
        END IF;

        INSERT INTO public.secret_shares (
            secret_id, shared_with_user_id, shared_with_role_id, shared_with_org_id,
            shared_by, access_level, expires_at, notes
        ) VALUES (
            p_secret_id, p_target_user_id, NULL, NULL,
            auth.uid(), 'read', p_expires_at, p_notes
        )
        ON CONFLICT (secret_id, shared_with_user_id)
        DO UPDATE SET expires_at = EXCLUDED.expires_at, notes = EXCLUDED.notes, created_at = now();
    END IF;

    IF p_target_role_id IS NOT NULL THEN
        -- NEW: role targets require `secret.share.role`.
        --
        -- Owning a secret has never been enough to justify this. A global role is not a
        -- person: `user` is a descendant of every role, so a share with it is visible to
        -- every account in the deployment (20260802010000 says so in its own header), and
        -- there is no undo for a credential a whole company has read. Until now the only
        -- thing standing between any secret owner and that button was that non-admins
        -- could not see the Secret Manager panel at all -- which is exactly what this
        -- migration stops being true.
        --
        -- User targets and organisation targets are deliberately NOT gated: the first is
        -- one named person, and the second already requires membership of the target org
        -- (checked below), which is the anti-phishing gate that branch needs. Neither
        -- fans out beyond people the sharer can already name.
        IF NOT public.authorize('secret.share.role') THEN
            RETURN jsonb_build_object('success', false, 'error',
                'Unauthorized: sharing with a role requires the secret.share.role permission. '
                || 'Share with a specific user or with your organisation instead.');
        END IF;

        SELECT r.name INTO v_target_role_name FROM public.roles r WHERE r.id = p_target_role_id;
        IF v_target_role_name IS NULL THEN
            RETURN jsonb_build_object('success', false, 'error', 'Role not found');
        END IF;

        INSERT INTO public.secret_shares (
            secret_id, shared_with_user_id, shared_with_role_id, shared_with_org_id,
            shared_by, access_level, expires_at, notes
        ) VALUES (
            p_secret_id, NULL, p_target_role_id, NULL,
            auth.uid(), 'read', p_expires_at, p_notes
        )
        ON CONFLICT (secret_id, shared_with_role_id)
        DO UPDATE SET expires_at = EXCLUDED.expires_at, notes = EXCLUDED.notes, created_at = now();
    END IF;

    IF p_target_org_id IS NOT NULL THEN
        SELECT o.slug INTO v_target_org_slug
        FROM public.organisations o WHERE o.id = p_target_org_id;
        IF v_target_org_slug IS NULL THEN
            RETURN jsonb_build_object('success', false, 'error', 'Organisation not found');
        END IF;

        -- You may only share INTO an organisation you belong to. Otherwise any
        -- user could push a credential at an arbitrary organisation's members --
        -- a phishing primitive, since the Secret Manager would then display it
        -- to them as a legitimately shared secret.
        IF NOT public.is_org_member(p_target_org_id) THEN
            RETURN jsonb_build_object('success', false, 'error',
                'You can only share with an organisation you belong to');
        END IF;

        INSERT INTO public.secret_shares (
            secret_id, shared_with_user_id, shared_with_role_id, shared_with_org_id,
            shared_by, access_level, expires_at, notes
        ) VALUES (
            p_secret_id, NULL, NULL, p_target_org_id,
            auth.uid(), 'read', p_expires_at, p_notes
        )
        ON CONFLICT (secret_id, shared_with_org_id)
        DO UPDATE SET expires_at = EXCLUDED.expires_at, notes = EXCLUDED.notes, created_at = now();
    END IF;

    v_via := CASE WHEN v_is_owner THEN 'owner'
                  WHEN v_is_org_admin THEN 'org_admin'
                  ELSE 'admin_override' END;

    INSERT INTO public.secret_access_log (
        secret_id, user_id, operation, access_granted_via, metadata
    ) VALUES (
        p_secret_id, auth.uid(), 'share', v_via,
        jsonb_build_object(
            'target_user_id', p_target_user_id,
            'target_role_id', p_target_role_id,
            'target_org_id', p_target_org_id,
            'target_email', v_target_email,
            'target_role_name', v_target_role_name,
            'target_org_slug', v_target_org_slug,
            'secret_website', v_secret_website,
            'expires_at', p_expires_at,
            'notes', p_notes
        )
    );

    RETURN jsonb_build_object(
        'success', true,
        'message', 'Secret shared successfully',
        'target_email', v_target_email,
        'target_role', v_target_role_name,
        'target_org', v_target_org_slug
    );
END;
$$;


ALTER FUNCTION "public"."share_secret"("uuid","uuid","uuid","text",timestamp with time zone,"uuid") OWNER TO "postgres";

COMMENT ON FUNCTION "public"."share_secret"("uuid","uuid","uuid","text",timestamp with time zone,"uuid") IS 'Shares a secret with exactly one of a user, a role, or an organisation. Authorization is can_manage_secret, so organisation admins can share organisation-owned secrets. Sharing INTO an organisation requires membership of it, which prevents pushing a credential at strangers. Sharing with a ROLE additionally requires secret.share.role: a role share fans out to every holder, and `user` is a descendant of every role, so an ungated role target is a one-click broadcast to the whole deployment. access_granted_via gains org_admin.';
