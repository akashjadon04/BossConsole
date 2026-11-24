-- pgTAP tests for membership and the join/request decision
-- (migrations 20260801030000, 20260801000000).
-- Run with: supabase test db
--
-- organisation_available_action is the single source of truth: join_organisation
-- and request_organisation_membership both consult it rather than re-reading the
-- join policy, so most of these assertions are about that one function and the
-- two entry points agreeing with it.

begin;
select plan(42);

-- ---------------------------------------------------------------------------
-- Fixtures: one organisation per join policy, plus a domain-verified one.
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('50000000-0000-0000-0000-000000000001', 'memowner@pgtap.test',   now()),
    ('50000000-0000-0000-0000-000000000002', 'memjoiner@pgtap.test',  now()),
    ('50000000-0000-0000-0000-000000000003', 'memasker@pgtap.test',   now()),
    ('50000000-0000-0000-0000-000000000004', 'staff@memcorp.test',    now()),
    ('50000000-0000-0000-0000-000000000005', 'unconfirmed@memcorp.test', null);

select public.create_organisation_internal(
    p_slug=>'pgtopen', p_name=>'Open Org',
    p_owner_id=>'50000000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'open');
select public.create_organisation_internal(
    p_slug=>'pgtreq', p_name=>'Request Org',
    p_owner_id=>'50000000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'request_to_join');
select public.create_organisation_internal(
    p_slug=>'pgtinvo', p_name=>'Invite Org',
    p_owner_id=>'50000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);


-- ===========================================================================
-- organisation_available_action
-- ===========================================================================
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000002',
        (select id from public.organisations where slug='pgtopen')),
    'join', 'an open organisation offers join'
);
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000003',
        (select id from public.organisations where slug='pgtreq')),
    'request', 'a request_to_join organisation offers request'
);
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000003',
        (select id from public.organisations where slug='pgtinvo')),
    'none', 'an invite-only organisation offers nothing'
);
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000001',
        (select id from public.organisations where slug='pgtopen')),
    'member', 'the owner is already a member'
);
select is(
    public.organisation_available_action(null,
        (select id from public.organisations where slug='pgtopen')),
    'none', 'a null user is offered nothing rather than erroring'
);
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000002',
        '00000000-0000-0000-0000-0000000000ff'),
    'none', 'a missing organisation is offered nothing'
);


-- ===========================================================================
-- join_organisation
-- ===========================================================================
select is(
    (select public.join_organisation(
        (select id from public.organisations where slug='pgtopen'),
        '50000000-0000-0000-0000-000000000002') ->> 'status'),
    'active', 'joining an open organisation is immediate'
);
select is(
    (select count(*)::int from public.organisation_members m
      join public.organisations o on o.id = m.org_id
     where o.slug='pgtopen' and m.user_id='50000000-0000-0000-0000-000000000002'
       and m.status='active'),
    1, 'the membership row is active'
);

-- The default member role is assigned on join, which is what makes the
-- organisation's permissions reach a new member at all.
select ok(
    exists (
        select 1 from public.user_roles ur
        join public.organisation_roles orl on orl.role_id = ur.role_id
        join public.organisations o on o.id = orl.org_id
        where o.slug='pgtopen' and ur.user_id='50000000-0000-0000-0000-000000000002'
          and orl.kind='user'
    ),
    'joining assigns the organisation''s default member role'
);

select is(
    (select public.join_organisation(
        (select id from public.organisations where slug='pgtopen'),
        '50000000-0000-0000-0000-000000000002') ->> 'already_member'),
    'true', 'joining twice is idempotent, not an error'
);

select is(
    (select public.join_organisation(
        (select id from public.organisations where slug='pgtreq'),
        '50000000-0000-0000-0000-000000000003') ->> 'error'),
    'This organisation requires approval -- use request_organisation_membership',
    'join is refused where approval is required, and says which call to make'
);
select is(
    (select public.join_organisation(
        (select id from public.organisations where slug='pgtinvo'),
        '50000000-0000-0000-0000-000000000003') ->> 'error'),
    'This organisation is invite-only',
    'join is refused for an invite-only organisation'
);
select is(
    (select public.join_organisation(
        (select id from public.organisations where slug='pgtopen'), null) ->> 'error'),
    'Not authenticated',
    'service_role with no named actor cannot join anybody'
);


-- ===========================================================================
-- request_organisation_membership
-- ===========================================================================
select is(
    (select public.request_organisation_membership(
        (select id from public.organisations where slug='pgtreq'),
        'please', '50000000-0000-0000-0000-000000000003') ->> 'status'),
    'pending', 'requesting membership leaves the applicant pending'
);

-- The load-bearing one: a pending applicant must hold NO organisation role.
select is(
    (select count(*)::int from public.user_roles ur
      join public.organisation_roles orl on orl.role_id = ur.role_id
      join public.organisations o on o.id = orl.org_id
     where o.slug='pgtreq' and ur.user_id='50000000-0000-0000-0000-000000000003'),
    0,
    'a PENDING applicant holds no organisation role -- approval is what grants it'
);

select is(
    (select public.request_organisation_membership(
        (select id from public.organisations where slug='pgtreq'),
        null, '50000000-0000-0000-0000-000000000003') ->> 'status'),
    'pending', 'requesting twice is idempotent'
);
select is(
    (select public.request_organisation_membership(
        (select id from public.organisations where slug='pgtopen'),
        null, '50000000-0000-0000-0000-000000000004') ->> 'error'),
    'This organisation can be joined directly -- use join_organisation',
    'requesting is refused where joining is open, and says which call to make'
);
select is(
    (select public.request_organisation_membership(
        (select id from public.organisations where slug='pgtinvo'),
        null, '50000000-0000-0000-0000-000000000003') ->> 'error'),
    'This organisation is invite-only',
    'requesting is refused for an invite-only organisation'
);


-- ===========================================================================
-- Approval and removal
-- ===========================================================================
select is(
    (select public.approve_organisation_member(
        (select id from public.organisations where slug='pgtreq'),
        '50000000-0000-0000-0000-000000000003',
        '50000000-0000-0000-0000-000000000002') ->> 'error'),
    'Permission denied',
    'a non-admin cannot approve a pending member'
);
select ok(
    (select public.approve_organisation_member(
        (select id from public.organisations where slug='pgtreq'),
        '50000000-0000-0000-0000-000000000003',
        '50000000-0000-0000-0000-000000000001') ->> 'success')::boolean,
    'an organisation admin can approve'
);
select ok(
    exists (
        select 1 from public.user_roles ur
        join public.organisation_roles orl on orl.role_id = ur.role_id
        join public.organisations o on o.id = orl.org_id
        where o.slug='pgtreq' and ur.user_id='50000000-0000-0000-0000-000000000003'
          and orl.kind='user'
    ),
    'approval is what assigns the default member role'
);
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000003',
        (select id from public.organisations where slug='pgtreq')),
    'member', 'an approved applicant reads as a member'
);

select ok(
    (select public.remove_organisation_member(
        (select id from public.organisations where slug='pgtreq'),
        '50000000-0000-0000-0000-000000000003',
        '50000000-0000-0000-0000-000000000001') ->> 'success')::boolean,
    'an admin can remove a member'
);
select is(
    (select count(*)::int from public.user_roles ur
      join public.organisation_roles orl on orl.role_id = ur.role_id
      join public.organisations o on o.id = orl.org_id
     where o.slug='pgtreq' and ur.user_id='50000000-0000-0000-0000-000000000003'),
    0,
    'removal revokes the organisation roles too -- a stale role would outlive the membership'
);

-- The owner is the one member who cannot be removed: organisations.owner_id is
-- ON DELETE RESTRICT precisely so an organisation cannot be orphaned.
select isnt(
    (select public.remove_organisation_member(
        (select id from public.organisations where slug='pgtreq'),
        '50000000-0000-0000-0000-000000000001',
        '50000000-0000-0000-0000-000000000001') ->> 'error'),
    null,
    'the owner cannot be removed'
);


-- ===========================================================================
-- Domain-based entry
-- ===========================================================================
-- Added and verified through the real RPCs rather than a raw insert: that path
-- generates the verification_token the table requires, and exercises the admin
-- gate on both calls.
select public.add_organisation_domain(
    (select id from public.organisations where slug='pgtreq'),
    'memcorp.test', false, '50000000-0000-0000-0000-000000000001');
select public.mark_organisation_domain_verified(
    (select d.id from public.organisation_domains d
       join public.organisations o on o.id = d.org_id
      where o.slug='pgtreq' and d.domain='memcorp.test'),
    '50000000-0000-0000-0000-000000000001');

select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000004',
        (select id from public.organisations where slug='pgtreq')),
    'join',
    'a verified-domain address skips the approval queue on a request_to_join organisation'
);

-- An unconfirmed address must NOT: otherwise signing up as anyone@theirdomain
-- and never confirming would walk straight into their organisation.
select is(
    public.organisation_available_action('50000000-0000-0000-0000-000000000005',
        (select id from public.organisations where slug='pgtreq')),
    'request',
    'an UNCONFIRMED address at the same domain still has to ask'
);

-- ===========================================================================
-- A system organisation's roster is NOT a global directory
--
-- Every user is an active member of the seeded boss org and handle_new_user
-- keeps every future signup there, so a bare membership gate would let any
-- signed-in user page out the email address of every account in the deployment.
-- public.users deliberately lets a plain user read only their own row, so that
-- would be a change of posture. Non-admins see only themselves in a system org;
-- real organisations are unaffected.
-- ===========================================================================
-- The boss-org seed skips on an empty database (organisations.owner_id is NOT
-- NULL and there are no users at migration time), so a system organisation is
-- created here instead. p_is_system bypasses the reserved-slug check, and the
-- explicit role names avoid the boss_admin collision the same way the seed does.
select public.create_organisation_internal(
    p_slug=>'pgtsys', p_name=>'System Org',
    p_owner_id=>'50000000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'open',
    p_is_system=>true,
    p_admin_role_name=>'pgtsys_org_admin',
    p_user_role_name=>'pgtsys_org_user');

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
select o.id, u.id, 'active', now(), 'seed'
  from public.organisations o
  cross join (values
      ('50000000-0000-0000-0000-000000000002'::uuid),
      ('50000000-0000-0000-0000-000000000003'::uuid)
  ) as u(id)
 where o.slug = 'pgtsys'
on conflict (org_id, user_id) do nothing;

select is(
    (select public.list_organisation_members(
        (select id from public.organisations where slug='pgtsys'),
        null, null, 500, 0, '50000000-0000-0000-0000-000000000002') ->> 'system_org_restricted'),
    'true',
    'a non-admin gets the restricted projection for a system organisation'
);
select is(
    (select jsonb_array_length(
        public.list_organisation_members(
            (select id from public.organisations where slug='pgtsys'),
            null, null, 500, 0, '50000000-0000-0000-0000-000000000002') -> 'data')),
    1,
    'and sees exactly one row -- their own -- not the whole deployment'
);
select is(
    (select e ->> 'user_id'
       from jsonb_array_elements(
            public.list_organisation_members(
                (select id from public.organisations where slug='pgtsys'),
                null, null, 500, 0, '50000000-0000-0000-0000-000000000002') -> 'data') e),
    '50000000-0000-0000-0000-000000000002',
    'and that row is theirs'
);
-- An ADMIN of the system organisation still gets the full roster: this restricts
-- who may read a directory, not whether one exists.
select ok(
    (select jsonb_array_length(
        public.list_organisation_members(
            (select id from public.organisations where slug='pgtsys'),
            null, null, 500, 0, '50000000-0000-0000-0000-000000000001') -> 'data')) > 1,
    'an admin of the system organisation still sees everyone'
);

-- A REAL organisation is unaffected: its roster is the whole point.
select ok(
    (select jsonb_array_length(
        public.list_organisation_members(
            (select id from public.organisations where slug='pgtopen'),
            null, null, 100, 0, '50000000-0000-0000-0000-000000000002') -> 'data')) > 1,
    'a non-system organisation still shows its full roster to a member'
);
select is(
    (select public.list_organisation_members(
        (select id from public.organisations where slug='pgtopen'),
        null, null, 100, 0, '50000000-0000-0000-0000-000000000002') ->> 'system_org_restricted'),
    null,
    'and is not flagged as restricted'
);

-- ===========================================================================
-- Arbitrary-subject helpers must not be callable by authenticated
--
-- Each reduces to a question about SOMEONE ELSE. user_can_view_plugin_row was
-- granted to anon and authenticated, which made it an oracle for
-- user_is_org_admin, user_is_org_member and is_user_admin all at once.
-- ===========================================================================
select ok(
    NOT has_function_privilege('authenticated',
        'public.user_can_view_plugin_row(uuid,text,uuid,uuid,boolean)', 'execute')
    AND NOT has_function_privilege('anon',
        'public.user_can_view_plugin_row(uuid,text,uuid,uuid,boolean)', 'execute'),
    'the arbitrary-subject plugin-visibility helper is not callable by anon or authenticated'
);
select ok(
    has_function_privilege('anon',
        'public.can_view_plugin_row(text,uuid,uuid,boolean)', 'execute')
    AND has_function_privilege('authenticated',
        'public.can_view_plugin_row(text,uuid,uuid,boolean)', 'execute'),
    'but the self-subject form is, so the store''s RLS still works anonymously'
);
select ok(
    NOT has_function_privilege('authenticated',
        'public.effective_share_role_ids(uuid)', 'execute'),
    'the arbitrary-subject share-role closure is not callable by authenticated'
);
select ok(
    has_function_privilege('authenticated',
        'public.my_effective_share_role_ids()', 'execute'),
    'but the self-subject form is, so the secret_shares policy still works'
);

-- An unverified domain may not be primary, and must not surface as one.
select is(
    (select public.set_primary_organisation_domain(
        (select d.id from public.organisation_domains d
           join public.organisations o on o.id = d.org_id
          where o.slug='pgtreq' and d.domain='memcorp.test'),
        '50000000-0000-0000-0000-000000000001') ->> 'success'),
    'true',
    'a VERIFIED domain can be made primary'
);
select public.add_organisation_domain(
    (select id from public.organisations where slug='pgtreq'),
    'unverified.test', false, '50000000-0000-0000-0000-000000000001');
select is(
    (select public.set_primary_organisation_domain(
        (select d.id from public.organisation_domains d
           join public.organisations o on o.id = d.org_id
          where o.slug='pgtreq' and d.domain='unverified.test'),
        '50000000-0000-0000-0000-000000000001') ->> 'error'),
    'A domain must be verified before it can be made primary',
    'an UNVERIFIED domain cannot -- enforced in the RPC, not only in the edge function'
);

-- An emptied description must actually clear.
--
-- COALESCE(p_description, description) treats an explicit empty string as "leave unchanged", so
-- an admin who cleared the textarea got ?ok=settings_saved with the old text still on the page -
-- a no-op reported as success, the same shape as the max_uses bug.
select public.update_organisation_settings(
    (select id from public.organisations where slug='pgtopen'),
    p_description => 'something to clear',
    p_actor_id => '50000000-0000-0000-0000-000000000001');
select is(
    (select description from public.organisations where slug='pgtopen'),
    'something to clear',
    'a description can be set'
);
select public.update_organisation_settings(
    (select id from public.organisations where slug='pgtopen'),
    p_description => '',
    p_actor_id => '50000000-0000-0000-0000-000000000001');
select is(
    (select description from public.organisations where slug='pgtopen'),
    null,
    'and an EMPTY string clears it rather than silently keeping the old one'
);
select public.update_organisation_settings(
    (select id from public.organisations where slug='pgtopen'),
    p_name => 'Renamed Open Org',
    p_actor_id => '50000000-0000-0000-0000-000000000001');
select is(
    (select name from public.organisations where slug='pgtopen'),
    'Renamed Open Org',
    'while an ABSENT description still leaves it alone'
);

select * from finish();
rollback;
