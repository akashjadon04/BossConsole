-- ============================================================================
-- BOSS Database Schema: who may DOWNLOAD a plugin
-- ============================================================================
-- File: 20260805000000_plugin_install_visibility.sql
-- Description:
--   user_can_install_plugin(p_user_id, p_plugin_id) -- the gate for the plugin
--   store's download route.
--
--   WHY THIS IS NOT user_can_view_plugin. Visibility and installability are
--   different questions, and 'unlisted' is exactly where they diverge:
--
--     unlisted = "installable by link, but absent from listings and search"
--
--   user_can_view_plugin answers the LISTING question, so it deliberately
--   returns false for an ordinary member looking at an unlisted plugin (see the
--   comment on user_can_view_plugin_row). Gating downloads on it would mean an
--   organisation could mark a plugin unlisted, hand the link to its members,
--   and every one of them would get a 404 -- the feature would be
--   admin-and-author-only, which is not what unlisted means.
--
--   So: installable = viewable, OR unlisted-and-published within an
--   organisation the caller belongs to.
--
--   Everything else is inherited unchanged, which matters more than the extra
--   clause: public-and-published stays anonymous, an author still reaches their
--   own drafts, a global admin still reaches everything, and a private
--   organisation's plugins stay unreachable by a non-member.
--
-- Dependencies:
--   - 20260803000000_plugins_org_ownership.sql (user_can_view_plugin_row,
--     user_is_org_member)
-- ============================================================================


CREATE OR REPLACE FUNCTION "public"."user_can_install_plugin"(
    "p_user_id" "uuid",
    "p_plugin_id" "uuid"
) RETURNS boolean
    LANGUAGE "sql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
    SELECT EXISTS (
        SELECT 1
        FROM public.plugins p
        WHERE p.id = p_plugin_id
          AND (
              public.user_can_view_plugin_row(
                  p_user_id, p.visibility, p.org_id, p.author_id, p.published)
              OR (
                  -- The link-holder case. Still requires publication and still
                  -- requires membership: a link is not an authorisation, it only
                  -- tells you the plugin exists.
                  p.visibility = 'unlisted'
                  AND p.published
                  AND p_user_id IS NOT NULL
                  AND public.user_is_org_member(p_user_id, p.org_id)
              )
          )
    );
$$;

ALTER FUNCTION "public"."user_can_install_plugin"("uuid", "uuid") OWNER TO "postgres";

-- service_role only, like user_can_view_plugin: the caller is the plugin-store
-- edge function, and an authenticated client asking "may user X install plugin
-- Y" for arbitrary X is an enumeration surface with no legitimate use.
REVOKE EXECUTE ON FUNCTION "public"."user_can_install_plugin"("uuid", "uuid")
    FROM PUBLIC, "anon", "authenticated";
GRANT  EXECUTE ON FUNCTION "public"."user_can_install_plugin"("uuid", "uuid") TO "service_role";

COMMENT ON FUNCTION "public"."user_can_install_plugin"("uuid", "uuid") IS
'Whether a user may download a plugin. Wider than user_can_view_plugin by exactly one case: an unlisted published plugin is installable by any member of its organisation, because "unlisted" means absent from listings, not un-installable. Called by the plugin-store download route with the service-role client.';


-- ============================================================================
-- End of File: 20260805000000_plugin_install_visibility.sql
-- ============================================================================
