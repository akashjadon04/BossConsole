-- pgTAP tests for organisation invite links (migration 20260801040000).
-- Run with: supabase test db
--
-- Covers minting constraints, the enumeration-oracle property (all failure modes
-- return one identical message), max_uses race safety, and -- the case a
-- behavioural test caught -- that idempotency is evaluated BEFORE exhaustion, so
-- re-clicking your own single-use link says "already a member" rather than
-- "invalid or expired".

begin;
select plan(28);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('10000000-0000-0000-0000-000000000001', 'invowner@pgtap.test',  now()),
    ('10000000-0000-0000-0000-000000000002', 'invjoiner@pgtap.test', now()),
    ('10000000-0000-0000-0000-000000000003', 'invother@pgtap.test',  now());

select public.create_organisation_internal(
    p_slug=>'pgtinv', p_name=>'PGTap Invites',
    p_owner_id=>'10000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

-- Act as the edge function would for the admin-side calls.
select set_config('request.jwt.claims', '{"role":"service_role"}', true);


-- ===========================================================================
-- Minting constraints
-- ===========================================================================
select is(
    (select public.create_organisation_invite(
        (select id from public.organisations where slug='pgtinv'),
        null, 'x', null, 168,
        '10000000-0000-0000-0000-000000000002') ->> 'error'),
    'Permission denied',
    'a non-admin cannot mint an invite'
);
select is(
    (select public.create_organisation_invite(
        (select id from public.organisations where slug='pgtinv'),
        (select orl.role_id from public.organisation_roles orl
           join public.organisations o on o.id=orl.org_id
          where o.slug='pgtinv' and orl.kind='admin'),
        null, null, 168, '10000000-0000-0000-0000-000000000001') ->> 'error'),
    'An invite link cannot grant the administrator role -- assign it explicitly after they join',
    'an invite may not grant the admin-kind role (a leaked URL would be an org takeover)'
);
select is(
    (select public.create_organisation_invite(
        (select id from public.organisations where slug='pgtinv'),
        null, null, null, 0, '10000000-0000-0000-0000-000000000001') ->> 'error'),
    'Expiry must be between 1 and 720 hours (30 days)',
    'expiry of 0 hours is refused'
);
select is(
    (select public.create_organisation_invite(
        (select id from public.organisations where slug='pgtinv'),
        null, null, null, 1000, '10000000-0000-0000-0000-000000000001') ->> 'error'),
    'Expiry must be between 1 and 720 hours (30 days)',
    'expiry beyond 30 days is refused -- there is no unbounded option'
);
select is(
    (select public.create_organisation_invite(
        (select id from public.organisations where slug='pgtinv'),
        null, null, 0, 168, '10000000-0000-0000-0000-000000000001') ->> 'error'),
    'max_uses must be at least 1',
    'max_uses of 0 is refused'
);

-- ===========================================================================
-- Token shape and storage
-- ===========================================================================
create temporary table t_inv (token text, invite_id uuid);
insert into t_inv
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinv'),
    null, 'single-use', 1, 24, '10000000-0000-0000-0000-000000000001') r;

select matches(
    (select token from t_inv),
    '^boss_inv_[A-Za-z0-9_-]{40,}$',
    'the token is URL-safe: boss_inv_ plus base64url with no + / or = padding'
);
select is(
    (select count(*)::int from public.organisation_invites
      where token_hash = (select token from t_inv)),
    0, 'the PLAINTEXT token is never stored -- only its hash'
);
select is(
    (select count(*)::int from public.organisation_invites i
      join t_inv t on t.invite_id = i.id
     where i.token_hash = encode(extensions.digest((select token from t_inv), 'sha256'), 'hex')),
    1, 'the stored hash is sha256 of the plaintext'
);
select is(
    (select count(*)::int from jsonb_object_keys(
        (public.list_organisation_invites(
            (select id from public.organisations where slug='pgtinv'),
            '10000000-0000-0000-0000-000000000001') -> 'data' -> 0)) k
      where k = 'token_hash'),
    0, 'list_organisation_invites NEVER projects token_hash'
);


-- ===========================================================================
-- Redemption. redeem_organisation_invite takes no p_actor_id -- it is always the
-- signed-in user -- so these run with a real JWT sub.
-- ===========================================================================
select set_config('request.jwt.claims',
    '{"sub":"10000000-0000-0000-0000-000000000002","role":"authenticated"}', true);

select is(
    (select public.redeem_organisation_invite((select token from t_inv)) ->> 'success'),
    'true', 'the happy path redeems'
);
select is(
    (select m.status from public.organisation_members m
      join public.organisations o on o.id = m.org_id
     where o.slug='pgtinv' and m.user_id='10000000-0000-0000-0000-000000000002'),
    'active', 'the redeemer becomes an active member'
);
select is(
    (select m.join_source from public.organisation_members m
      join public.organisations o on o.id = m.org_id
     where o.slug='pgtinv' and m.user_id='10000000-0000-0000-0000-000000000002'),
    'invite', 'join_source records how they got in'
);
select is(
    (select uses from public.organisation_invites i join t_inv t on t.invite_id = i.id),
    1, 'one use is burned'
);

-- THE ORDERING CASE. This invite has max_uses = 1 and is now exhausted, so a
-- validity-first implementation answers "invalid or expired" to the very person
-- who just used it successfully. Idempotency must be checked first.
select is(
    (select public.redeem_organisation_invite((select token from t_inv)) ->> 'already_member'),
    'true',
    'ORDERING: re-redeeming your OWN exhausted single-use link reports already_member, not an error'
);
select is(
    (select uses from public.organisation_invites i join t_inv t on t.invite_id = i.id),
    1, 'ORDERING: the repeat redemption does not burn a second use'
);
select is(
    (select count(*)::int from public.organisation_invite_redemptions red
      join t_inv t on t.invite_id = red.invite_id),
    1, 'exactly one redemption row exists for the repeat click'
);

-- A DIFFERENT user hitting the now-exhausted link gets the generic message.
select set_config('request.jwt.claims',
    '{"sub":"10000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite((select token from t_inv)) ->> 'error'),
    'Invite link is invalid or expired',
    'a second user cannot use an exhausted max_uses=1 link'
);

-- ===========================================================================
-- The enumeration-oracle property: every failure mode, one identical message.
-- ===========================================================================
select is(
    (select public.redeem_organisation_invite('boss_inv_completely_made_up_token') ->> 'error'),
    'Invite link is invalid or expired',
    'ORACLE: an unknown token yields the generic message'
);

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
create temporary table t_rev (token text, invite_id uuid);
insert into t_rev
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinv'),
    null, 'to-revoke', null, 24, '10000000-0000-0000-0000-000000000001') r;
select public.revoke_organisation_invite(
    (select invite_id from t_rev), '10000000-0000-0000-0000-000000000001');

select set_config('request.jwt.claims',
    '{"sub":"10000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite((select token from t_rev)) ->> 'error'),
    'Invite link is invalid or expired',
    'ORACLE: a revoked token yields the SAME message -- "revoked" would confirm it existed'
);

-- Expired: set expires_at into the past directly.
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
create temporary table t_exp (token text, invite_id uuid);
insert into t_exp
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinv'),
    null, 'to-expire', null, 24, '10000000-0000-0000-0000-000000000001') r;
update public.organisation_invites set expires_at = now() - interval '1 hour'
 where id = (select invite_id from t_exp);

select set_config('request.jwt.claims',
    '{"sub":"10000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select is(
    (select public.redeem_organisation_invite((select token from t_exp)) ->> 'error'),
    'Invite link is invalid or expired',
    'ORACLE: an expired token yields the SAME message'
);

-- ===========================================================================
-- The unauthenticated preview never redeems.
-- ===========================================================================
select set_config('request.jwt.claims', '{"role":"service_role"}', true);
create temporary table t_prev (token text, invite_id uuid);
insert into t_prev
select r ->> 'token', (r ->> 'invite_id')::uuid
from public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinv'),
    null, 'preview', 1, 24, '10000000-0000-0000-0000-000000000001') r;

select is(
    (select public.get_organisation_invite_preview((select token from t_prev)) ->> 'name'),
    'PGTap Invites',
    'the preview shows the organisation name so the landing page is not blank'
);
select is(
    (select uses from public.organisation_invites i join t_prev t on t.invite_id = i.id),
    0,
    'PREFETCH SAFETY: previewing does NOT consume a use, so an email scanner cannot burn an invite'
);
select is(
    (select public.get_organisation_invite_preview('boss_inv_nope') ->> 'valid'),
    'false',
    'the preview reports valid=false for an unknown token without revealing anything'
);

-- ===========================================================================
-- Re-click after removal must re-admit, not dead-end
--
-- The redemption row outlives the membership: there is no 'removed' status, so
-- remove_organisation_member DELETES the member row while the redemption
-- survives. Keying idempotency on the redemption alone told someone who had
-- been removed "already a member" and did not re-add them - a dead end they
-- could never escape through that link.
-- ===========================================================================
create temporary table t_rejoin as
select public.create_organisation_invite(
    (select id from public.organisations where slug='pgtinv'),
    null, 'rejoin', 5, 168, '10000000-0000-0000-0000-000000000001') as r;

select set_config('request.jwt.claims',
    '{"sub":"10000000-0000-0000-0000-000000000003","role":"authenticated"}', true);

select ok(
    (select public.redeem_organisation_invite((select r ->> 'token' from t_rejoin))
        ->> 'success')::boolean,
    'the invite admits the user the first time'
);

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.remove_organisation_member(
    (select id from public.organisations where slug='pgtinv'),
    '10000000-0000-0000-0000-000000000003',
    '10000000-0000-0000-0000-000000000001');

select is(
    (select count(*)::int from public.organisation_members m
       join public.organisations o on o.id = m.org_id
      where o.slug='pgtinv' and m.user_id='10000000-0000-0000-0000-000000000003'
        and m.status='active'),
    0,
    'removal really did drop the membership'
);

select set_config('request.jwt.claims',
    '{"sub":"10000000-0000-0000-0000-000000000003","role":"authenticated"}', true);

select is(
    (select public.redeem_organisation_invite((select r ->> 'token' from t_rejoin))
        ->> 'already_member'),
    null,
    'the same live link does NOT report already_member after removal'
);
select is(
    (select count(*)::int from public.organisation_members m
       join public.organisations o on o.id = m.org_id
      where o.slug='pgtinv' and m.user_id='10000000-0000-0000-0000-000000000003'
        and m.status='active'),
    1,
    'it re-admits them instead of dead-ending'
);

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

-- uses must not double-count on a re-admit.
--
-- The re-click-after-removal path deliberately falls through and re-admits, which is exactly the
-- case that produced one redemption row and two increments while the UPDATE was unconditional -
-- burning a use of a capped link on somebody who had already consumed one.
select is(
    (select i.uses from public.organisation_invites i
       where i.token_prefix = (select r ->> 'token_prefix' from t_rejoin))::int,
    1,
    'a re-admit through the same link does not increment uses a second time'
);

select * from finish();
rollback;
