-- ============================================================================
-- BOSS Database Schema: reading the organisation-request queue is its own permission
--
-- File: 20260816000000_organisation_request_read.sql
--
-- Seeing the organisation-creation request queue was gated on organisation.approve,
-- which 20260801010000 grants to TWO roles: admin and boss_admin. So every
-- boss_admin could list every pending request, including the requester's email and
-- the free-text justification. The requirement is that only `admin` sees that queue
-- for now, and that widening it later is a grant rather than a migration.
--
-- WHY A NEW PERMISSION RATHER THAN REVOKING organisation.approve FROM boss_admin:
-- organisation.approve means more than one thing. It gates acting on a request
-- (approve/reject) and it gates the organisations SELECT policy that lets a reviewer
-- see private organisations in order to judge a name clash. Revoking it would take
-- all three away together, and it cannot be un-granted from the roles plugin at all,
-- because that UI hides the Remove button for is_system permissions while its
-- add-list does not filter them - a system permission is a one-way door there.
-- Splitting the read out leaves the other two capabilities exactly as they were.
--
-- WHY is_system = false, deliberately, unlike the four permissions beside it:
-- for exactly the reason above. This permission exists to be granted and un-granted
-- as the policy changes, so it must stay reachable from the roles plugin's UI. The
-- four organisation.* permissions in 20260801010000 are is_system because they are
-- structural; this one is policy.
--
-- WHY IT IS GRANTED TO NOBODY HERE: authorize() and user_holds_permission() both
-- short-circuit for global admins (20260625000000:212, 20260801010000), so `admin`
-- holds it the moment it exists and the queue keeps working with no grant at all.
-- The explicit grant to admin is left to the operator, so that the set of roles
-- holding it is a deliberate, auditable act rather than a side effect of a deploy.
--
-- NOT CHANGED, ON PURPOSE: approve_organisation_request, reject_organisation_request
-- and the "Organisation reviewers can view all organisations" policy still read
-- organisation.approve. They are separate capabilities, and a boss_admin keeping them
-- is the status quo, not a regression introduced here.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- The permission
-- ---------------------------------------------------------------------------
-- A plugin manifest cannot declare this one: register_plugin_permission refuses the
-- reserved domains (role, user, api_key, rpa, secret, plugins, organisation, org),
-- so an organisation.* permission has to be seeded here, as the other four were.
--
-- The name has exactly one dot because that is what the RBAC name check enforces
-- (^[a-z][a-z0-9_]{1,30}\.[a-z][a-z0-9_]{1,30}$) -- organisation.requests.view
-- would be rejected.

INSERT INTO "public"."permissions" ("name", "description", "is_system") VALUES
    ('organisation.request_read',
     'Read the global organisation-creation request queue. Distinct from organisation.approve, which is the power to act on a request. Not is_system, so it can be granted and revoked from the roles UI as the policy changes.',
     false)
ON CONFLICT ("name") DO NOTHING;


-- ---------------------------------------------------------------------------
-- Site 1: the RPC the desktop plugin actually calls
-- ---------------------------------------------------------------------------
-- Body copied verbatim from 20260808000000, which added r.website to the projection.
-- The ONLY change is the permission named on the v_is_reviewer line. Same signature,
-- same {success, is_reviewer, data} envelope, so no client changes shape.
--
-- v_is_reviewer is load-bearing twice over: it is returned to the client, which
-- renders the Requests button on it, AND it is the row filter -- so a caller without
-- the permission does not merely lose the button, they stop seeing other people's
-- rows at all. Both halves move together by construction.

CREATE OR REPLACE FUNCTION "public"."list_organisation_requests"(
    "p_status" "text" DEFAULT NULL::"text",
    "p_limit" integer DEFAULT 50,
    "p_offset" integer DEFAULT 0,
    "p_actor_id" "uuid" DEFAULT NULL::"uuid"
) RETURNS "jsonb"
    LANGUAGE "plpgsql" STABLE SECURITY DEFINER
    SET "search_path" TO ''
    AS $$
DECLARE
    v_actor UUID;
    v_is_reviewer BOOLEAN;
    v_rows JSONB;
BEGIN
    v_actor := public.resolve_org_actor(p_actor_id);
    IF v_actor IS NULL THEN
        RETURN jsonb_build_object('success', false, 'error', 'Not authenticated');
    END IF;

    v_is_reviewer := public.user_holds_permission(v_actor, 'organisation.request_read');

    SELECT COALESCE(jsonb_agg(row_to_json(t)::jsonb), '[]'::jsonb)
      INTO v_rows
      FROM (
        SELECT r.id, r.requester_id, ru.email AS requester_email,
               r.name, r.slug, r.description, r.domain, r.website, r.justification,
               r.status, r.reviewer_id, vu.email AS reviewer_email,
               r.review_notes, r.reviewed_at, r.created_org_id, r.created_at
        FROM public.organisation_requests r
        LEFT JOIN auth.users ru ON ru.id = r.requester_id
        LEFT JOIN auth.users vu ON vu.id = r.reviewer_id
        WHERE (v_is_reviewer OR r.requester_id = v_actor)
          AND (p_status IS NULL OR r.status = p_status)
        ORDER BY r.created_at DESC
        LIMIT GREATEST(LEAST(COALESCE(p_limit, 50), 200), 1)
        OFFSET GREATEST(COALESCE(p_offset, 0), 0)
      ) t;

    RETURN jsonb_build_object('success', true, 'is_reviewer', v_is_reviewer, 'data', v_rows);
END;
$$;

COMMENT ON FUNCTION "public"."list_organisation_requests"("text", integer, integer, "uuid") IS 'Organisation-creation requests. Holders of organisation.request_read see all; everyone else sees only their own. Returns is_reviewer so the client knows which UI to render.';


-- ---------------------------------------------------------------------------
-- Site 2: the direct table read
-- ---------------------------------------------------------------------------
-- Nothing reads organisation_requests through PostgREST today -- both clients go
-- through the RPC above. The policy is switched anyway, because leaving it on
-- organisation.approve would mean a boss_admin who lost the queue in the UI could
-- still select the same rows straight off the table, which is a gate that only
-- looks like one. "Requesters can view their own organisation requests" is
-- untouched, so a requester keeps seeing their own row either way.

DROP POLICY IF EXISTS "Reviewers can view all organisation requests" ON "public"."organisation_requests";
CREATE POLICY "Reviewers can view all organisation requests" ON "public"."organisation_requests"
    FOR SELECT TO "authenticated"
    USING ("public"."authorize"('organisation.request_read'));
