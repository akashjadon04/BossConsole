-- pgTAP tests for adopting a verified domain's existing users (20260810000000).
-- Run with: supabase test db
--
-- This is the first thing in the organisation schema that adds somebody to an
-- organisation without them acting, so the assertions are weighted towards what it
-- must REFUSE and who it must not touch. A test that only proved the happy path
-- would pass just as well against a version that adopted unconfirmed accounts, or
-- re-adopted somebody an admin had removed.
--
-- WHAT THESE ASSERTIONS ACTUALLY COVER, measured by deleting each guard and
-- re-running rather than by reading:
--
--   COVERED. Removing the admin gate fails 3 of these; the verified check, 4;
--   the confirmed-address filter, 3; the reserved-domain backstop, 1.
--
--   NOT COVERED, and worth stating so nobody assumes otherwise: the function has
--   THREE layers stopping an existing member being touched - the cursor's NOT
--   EXISTS, the INSERT's ON CONFLICT DO NOTHING, and the IF FOUND that guards the
--   counter. Deleting any ONE of them fails nothing here, because in a single
--   session the other two mask it: the cursor already excludes existing members,
--   so no conflicting insert is ever attempted and every iteration is a real one.
--   The case that separates them is a row appearing BETWEEN the cursor opening
--   and the insert, which one pgTAP session cannot produce. Those three are
--   defence in depth against concurrency and are unexercised; the behaviour they
--   protect (pending stays pending, invited stays invited, removed stays removed)
--   IS asserted below, just not attributable to a single layer.

begin;
select plan(23);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('a0000000-0000-4000-8000-000000000001', 'founder@acme.test',  now()),
    ('a0000000-0000-4000-8000-000000000002', 'alice@acme.test',    now()),
    ('a0000000-0000-4000-8000-000000000003', 'bob@acme.test',      now()),
    -- Never confirmed: registering an address at a domain proves nothing until
    -- the address answers.
    ('a0000000-0000-4000-8000-000000000004', 'ghost@acme.test',    null),
    -- A different domain entirely.
    ('a0000000-0000-4000-8000-000000000005', 'carol@other.test',   now()),
    -- Case: the stored domain is lower-cased, the address is not.
    ('a0000000-0000-4000-8000-000000000006', 'dave@ACME.test',     now()),
    ('a0000000-0000-4000-8000-000000000007', 'outsider@acme.test', now()),
    ('a0000000-0000-4000-8000-000000000008', 'stranger@acme.test', now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select ok(
    (public.create_organisation_internal(
        p_slug => 'acmeco', p_name => 'Acme Co', p_description => null,
        p_owner_id => 'a0000000-0000-4000-8000-000000000001',
        p_domain => null, p_visibility => 'private',
        p_join_policy => 'invite_only') ->> 'success')::boolean,
    'the organisation can be created'
);

create temporary table t_ids as
select (select id from public.organisations where slug = 'acmeco') as org_id;

select ok(
    (public.add_organisation_domain(
        (select org_id from t_ids), 'acme.test', true,
        'a0000000-0000-4000-8000-000000000001') ->> 'success')::boolean,
    'the domain can be claimed'
);

create temporary table t_dom as
select d.id as domain_id
  from public.organisation_domains d
 where d.org_id = (select org_id from t_ids) and d.domain = 'acme.test';


-- ===========================================================================
-- An UNVERIFIED domain adopts nobody
-- ===========================================================================
-- The whole authority for this action is the DNS proof. Without that check it
-- would be "type a domain, take its users".
select is(
    public.add_domain_users_to_organisation(
        (select domain_id from t_dom),
        'a0000000-0000-4000-8000-000000000001') ->> 'error',
    'The domain must be verified before its users can be added',
    'an unverified domain is refused'
);

select is(
    (select count(*)::int from public.organisation_members
      where org_id = (select org_id from t_ids)),
    1,
    'and nobody was added - only the founder is a member'
);

select is(
    public.count_domain_users_for_organisation((select domain_id from t_dom)),
    0,
    'the preview count is 0 while unverified, so no button is offered'
);


-- ===========================================================================
-- Verified: the count, then the adoption
-- ===========================================================================
update public.organisation_domains
   set verified = true, verified_at = now()
 where id = (select domain_id from t_dom);

-- alice, bob, dave (mixed case), outsider, stranger = 5. NOT ghost (unconfirmed),
-- NOT carol (other domain), NOT the founder (already a member).
select is(
    public.count_domain_users_for_organisation((select domain_id from t_dom)),
    5,
    'the preview counts exactly the accounts that would be adopted'
);

-- ===========================================================================
-- Authorisation
-- ===========================================================================
select is(
    public.add_domain_users_to_organisation(
        (select domain_id from t_dom),
        'a0000000-0000-4000-8000-000000000005') ->> 'error',
    'Permission denied',
    'a non-member cannot adopt an organisation''s domain users'
);

-- A plain MEMBER is not enough either: this hands out membership in bulk.
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
values ((select org_id from t_ids), 'a0000000-0000-4000-8000-000000000005', 'active', now(), 'admin');

select is(
    public.add_domain_users_to_organisation(
        (select domain_id from t_dom),
        'a0000000-0000-4000-8000-000000000005') ->> 'error',
    'Permission denied',
    'an ordinary member cannot either - it is an admin action'
);

delete from public.organisation_members
 where org_id = (select org_id from t_ids)
   and user_id = 'a0000000-0000-4000-8000-000000000005';

select is(
    public.add_domain_users_to_organisation(
        '99999999-9999-4999-8999-999999999999',
        'a0000000-0000-4000-8000-000000000001') ->> 'error',
    'Domain not found',
    'an unknown domain id is refused before anything else is read'
);


-- ===========================================================================
-- The adoption itself
-- ===========================================================================
create temporary table t_first as
select public.add_domain_users_to_organisation(
    (select domain_id from t_dom),
    'a0000000-0000-4000-8000-000000000001') as result;

select ok((select (result ->> 'success')::boolean from t_first), 'the admin can adopt');

select is(
    (select (result ->> 'added')::int from t_first),
    5,
    'it reports exactly what it inserted'
);

select is(
    (select count(*)::int from public.organisation_members
      where org_id = (select org_id from t_ids) and status = 'active'),
    6,
    'the five join the founder'
);

select is(
    (select join_source from public.organisation_members
      where org_id = (select org_id from t_ids)
        and user_id = 'a0000000-0000-4000-8000-000000000002'),
    'domain',
    'they are recorded as having joined by domain, not as an anonymous admin add'
);

-- A mixed-case address must be matched: auth.users stores what was typed, and the
-- domain column is lower-cased on the way in.
select ok(
    exists (select 1 from public.organisation_members
             where org_id = (select org_id from t_ids)
               and user_id = 'a0000000-0000-4000-8000-000000000006'),
    'an address with a capitalised domain is matched'
);

select ok(
    not exists (select 1 from public.organisation_members
                 where org_id = (select org_id from t_ids)
                   and user_id = 'a0000000-0000-4000-8000-000000000004'),
    'an unconfirmed address is NOT adopted'
);

select ok(
    not exists (select 1 from public.organisation_members
                 where org_id = (select org_id from t_ids)
                   and user_id = 'a0000000-0000-4000-8000-000000000005'),
    'an address at another domain is not adopted'
);


-- ===========================================================================
-- Re-running is inert, and the count agrees
-- ===========================================================================
select is(
    public.count_domain_users_for_organisation((select domain_id from t_dom)),
    0,
    'the preview drops to 0 once everybody has been adopted'
);

select is(
    (public.add_domain_users_to_organisation(
        (select domain_id from t_dom),
        'a0000000-0000-4000-8000-000000000001') ->> 'added')::int,
    0,
    're-running adds nobody and says so, rather than reporting the same five again'
);


-- ===========================================================================
-- Existing rows are never disturbed
-- ===========================================================================
-- The three that matter: a pending applicant must not be silently promoted, an
-- invited user must not be auto-accepted, and somebody an admin REMOVED must not
-- be re-adopted by the next press of the button.
delete from public.organisation_members
 where org_id = (select org_id from t_ids)
   and user_id in ('a0000000-0000-4000-8000-000000000007',
                   'a0000000-0000-4000-8000-000000000008');

insert into public.organisation_members (org_id, user_id, status, requested_at, join_source)
values ((select org_id from t_ids), 'a0000000-0000-4000-8000-000000000007', 'pending', now(), 'request');
insert into public.organisation_members (org_id, user_id, status, invited_at, join_source)
values ((select org_id from t_ids), 'a0000000-0000-4000-8000-000000000008', 'invited', now(), 'invite');

select is(
    (public.add_domain_users_to_organisation(
        (select domain_id from t_dom),
        'a0000000-0000-4000-8000-000000000001') ->> 'added')::int,
    0,
    'a pending and an invited row are both left alone'
);

select is(
    (select status from public.organisation_members
      where org_id = (select org_id from t_ids)
        and user_id = 'a0000000-0000-4000-8000-000000000007'),
    'pending',
    'the pending applicant still has to be approved'
);

select is(
    (select status from public.organisation_members
      where org_id = (select org_id from t_ids)
        and user_id = 'a0000000-0000-4000-8000-000000000008'),
    'invited',
    'the invited user still has to accept'
);


-- ===========================================================================
-- A reserved domain is refused even if a row somehow says verified
-- ===========================================================================
-- add_organisation_domain already refuses to claim one; this is the backstop for a
-- row that predates that rule, and it is the check that stops an organisation
-- adopting every gmail.com account in the database.
insert into public.reserved_email_domains (domain) values ('acme.test')
on conflict do nothing;

select is(
    public.add_domain_users_to_organisation(
        (select domain_id from t_dom),
        'a0000000-0000-4000-8000-000000000001') ->> 'error',
    '"acme.test" is a reserved email domain',
    'a reserved domain is refused even when the row is marked verified'
);

select is(
    public.count_domain_users_for_organisation((select domain_id from t_dom)),
    0,
    'and the preview refuses to count for one, so no button is offered'
);

select * from finish();
rollback;
