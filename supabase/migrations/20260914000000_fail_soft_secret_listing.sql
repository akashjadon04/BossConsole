-- ============================================================================
-- BOSS Database Schema: Fail soft when listing undecryptable secrets
-- ============================================================================
-- A single corrupted ciphertext must not prevent a user from loading every
-- other secret. This is intentionally a forward migration: the RPCs below are
-- already installed on deployed databases, so editing their historical source
-- would not repair them.
-- ============================================================================

-- This helper is deliberately not named safe_decrypt_*. The master-key rotation
-- workflow reserves that prefix for mapped, format-specific adapters.
CREATE OR REPLACE FUNCTION public.try_decrypt_text(ciphertext text) RETURNS text
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO ''
AS $$
BEGIN
    IF ciphertext IS NULL THEN
        RETURN NULL;
    END IF;

    BEGIN
        RETURN public.decrypt_text(ciphertext);
    EXCEPTION
        WHEN OTHERS THEN
            -- Do not include exception text or ciphertext: either can contain a secret.
            -- SQLSTATE lets operators correlate systemic decrypt failures safely.
            RAISE WARNING 'Secret decryption failed (SQLSTATE %)', SQLSTATE;
            RETURN NULL;
    END;
END;
$$;

ALTER FUNCTION public.try_decrypt_text(text) OWNER TO postgres;
COMMENT ON FUNCTION public.try_decrypt_text(text) IS
    'Decrypts ordinary secret ciphertext and returns NULL instead of raising on failure';

-- The helper is an implementation detail of authorized SECURITY DEFINER RPCs,
-- not an authenticated decryption oracle.
REVOKE EXECUTE ON FUNCTION public.try_decrypt_text(text)
    FROM PUBLIC, anon, authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.try_decrypt_text(text) TO postgres;

CREATE OR REPLACE FUNCTION public.get_user_secrets(
    p_limit integer DEFAULT 50,
    p_offset integer DEFAULT 0
) RETURNS TABLE(
    id uuid, website text, username text, password text, notes text,
    expiration_date timestamp with time zone, tags jsonb, metadata jsonb,
    created_at timestamp with time zone, updated_at timestamp with time zone,
    org_id uuid, org_slug text, is_org_owned boolean, can_manage boolean
)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO ''
AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.id, s.website, s.username,
        COALESCE(public.try_decrypt_text(s.password_encrypted), '') AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'recovery_codes', public.safe_decrypt_recovery_codes(sm.recovery_codes_encrypted)
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at,
        s.org_id,
        o.slug AS org_slug,
        (s.org_id IS NOT NULL) AS is_org_owned,
        (s.user_id = auth.uid()
            OR public.is_user_admin(auth.uid())
            OR (s.org_id IS NOT NULL AND public.is_org_admin(s.org_id))) AS can_manage
    FROM public.secrets s
    LEFT JOIN public.organisations o ON o.id = s.org_id
    WHERE s.user_id = auth.uid()
       OR (s.org_id IS NOT NULL AND public.is_org_member(s.org_id))
    ORDER BY s.created_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION public.get_user_secrets(integer, integer) OWNER TO postgres;

CREATE OR REPLACE FUNCTION public.search_user_secrets(
    p_query text,
    p_limit integer DEFAULT 50,
    p_offset integer DEFAULT 0
) RETURNS TABLE(
    id uuid, website text, username text, password text, notes text,
    expiration_date timestamp with time zone, tags jsonb, metadata jsonb,
    created_at timestamp with time zone, updated_at timestamp with time zone,
    org_id uuid, org_slug text, is_org_owned boolean, can_manage boolean
)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO ''
AS $$
BEGIN
    RETURN QUERY
    SELECT
        s.id, s.website, s.username,
        COALESCE(public.try_decrypt_text(s.password_encrypted), '') AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'recovery_codes', public.safe_decrypt_recovery_codes(sm.recovery_codes_encrypted)
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at,
        s.org_id,
        o.slug AS org_slug,
        (s.org_id IS NOT NULL) AS is_org_owned,
        (s.user_id = auth.uid()
            OR public.is_user_admin(auth.uid())
            OR (s.org_id IS NOT NULL AND public.is_org_admin(s.org_id))) AS can_manage
    FROM public.secrets s
    LEFT JOIN public.organisations o ON o.id = s.org_id
    WHERE (s.user_id = auth.uid()
           OR (s.org_id IS NOT NULL AND public.is_org_member(s.org_id)))
      AND (s.website ILIKE '%' || p_query || '%' OR s.username ILIKE '%' || p_query || '%')
    ORDER BY s.created_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION public.search_user_secrets(text, integer, integer) OWNER TO postgres;

CREATE OR REPLACE FUNCTION public.get_user_secrets_with_shared(
    p_limit integer DEFAULT 50,
    p_offset integer DEFAULT 0
) RETURNS TABLE(
    id uuid, website text, username text, password text, notes text,
    expiration_date timestamp with time zone, tags jsonb, metadata jsonb,
    created_at timestamp with time zone, updated_at timestamp with time zone,
    is_owner boolean, shared_by_email text, access_level text,
    org_id uuid, org_slug text, is_org_owned boolean,
    shared_with_org_slug text, can_manage boolean
)
    LANGUAGE plpgsql STABLE SECURITY DEFINER
    SET search_path TO ''
AS $$
BEGIN
    RETURN QUERY
    WITH accessible_secrets AS (
        SELECT s.id, TRUE AS is_owner, NULL::text AS shared_by_email,
               'owner'::text AS access_level, NULL::text AS shared_with_org_slug, 1 AS priority
        FROM public.secrets s
        WHERE s.user_id = auth.uid()

        UNION ALL

        SELECT s.id, (s.user_id = auth.uid()) AS is_owner, NULL::text,
               'org'::text, o.slug, 2
        FROM public.secrets s
        JOIN public.organisations o ON o.id = s.org_id
        WHERE s.org_id IS NOT NULL
          AND public.is_org_member(s.org_id)

        UNION ALL

        SELECT s.id, FALSE, u.email, ss.access_level, NULL::text, 3
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        WHERE ss.shared_with_user_id = auth.uid()
          AND (ss.expires_at IS NULL OR ss.expires_at > now())

        UNION ALL

        SELECT s.id, FALSE, u.email, ss.access_level, NULL::text, 4
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        WHERE ss.shared_with_role_id IN (SELECT public.effective_share_role_ids(auth.uid()))
          AND (ss.expires_at IS NULL OR ss.expires_at > now())

        UNION ALL

        SELECT s.id, FALSE, u.email, ss.access_level, o.slug, 5
        FROM public.secrets s
        JOIN public.secret_shares ss ON ss.secret_id = s.id
        JOIN auth.users u ON u.id = ss.shared_by
        JOIN public.organisations o ON o.id = ss.shared_with_org_id
        WHERE ss.shared_with_org_id IS NOT NULL
          AND public.is_org_member(ss.shared_with_org_id)
          AND (ss.expires_at IS NULL OR ss.expires_at > now())
    ),
    unique_secrets AS (
        SELECT DISTINCT ON (a.id)
            a.id, a.is_owner, a.shared_by_email, a.access_level, a.shared_with_org_slug
        FROM accessible_secrets a
        ORDER BY a.id, a.is_owner DESC, a.priority
    )
    SELECT
        s.id, s.website, s.username,
        COALESCE(public.try_decrypt_text(s.password_encrypted), '') AS password,
        s.notes, s.expiration_date,
        COALESCE((SELECT jsonb_agg(st.tag) FROM public.secret_tags st WHERE st.secret_id = s.id), '[]'::jsonb) AS tags,
        COALESCE((
            SELECT jsonb_build_object(
                'twofa_enabled', sm.twofa_enabled,
                'twofa_type', sm.twofa_type,
                'twofa_secret', public.safe_decrypt_twofa_secret(sm.twofa_secret),
                'recovery_codes', public.safe_decrypt_recovery_codes(sm.recovery_codes_encrypted)
            )
            FROM public.secret_metadata sm WHERE sm.secret_id = s.id
        ), '{}'::jsonb) AS metadata,
        s.created_at, s.updated_at,
        us.is_owner, us.shared_by_email, us.access_level,
        s.org_id,
        o.slug AS org_slug,
        (s.org_id IS NOT NULL) AS is_org_owned,
        us.shared_with_org_slug,
        (s.user_id = auth.uid()
            OR public.is_user_admin(auth.uid())
            OR (s.org_id IS NOT NULL AND public.is_org_admin(s.org_id))) AS can_manage
    FROM unique_secrets us
    JOIN public.secrets s ON s.id = us.id
    LEFT JOIN public.organisations o ON o.id = s.org_id
    ORDER BY s.created_at DESC, s.id DESC
    LIMIT p_limit OFFSET p_offset;
END;
$$;

ALTER FUNCTION public.get_user_secrets_with_shared(integer, integer) OWNER TO postgres;

GRANT EXECUTE ON FUNCTION public.get_user_secrets(integer, integer)
    TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.search_user_secrets(text, integer, integer)
    TO authenticated, service_role;
GRANT EXECUTE ON FUNCTION public.get_user_secrets_with_shared(integer, integer)
    TO authenticated, service_role;
