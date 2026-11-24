-- pgTAP tests for handoff tokens, JWT claims, discovery and table shape
-- (migrations 20260801000000, 20260801050000, 20260801060000, 20260801030000).
-- Run with: supabase test db
--
-- The handoff token is a bearer credential for its short life, so the
-- assertions that matter are single-use, expiry, and the absence of any
-- parameter naming a subject. The JWT hook assertions are about the grant that
-- breaks every login if it is missing.

begin;
select plan(49);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('70000000-0000-0000-0000-000000000001', 'hoowner@pgtap.test',    now()),
    ('70000000-0000-0000-0000-000000000002', 'homember@pgtap.test',   now()),
    ('70000000-0000-0000-0000-000000000003', 'hooutsider@pgtap.test', now());

select public.create_organisation_internal(
    p_slug=>'pgthpub', p_name=>'Public Handoff Org',
    p_description=>'findable',
    p_owner_id=>'70000000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'open');
select public.create_organisation_internal(
    p_slug=>'pgthpriv', p_name=>'Private Handoff Org',
    p_owner_id=>'70000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

select set_config('request.jwt.claims', '{"role":"service_role"}', true);
select public.join_organisation(
    (select id from public.organisations where slug='pgthpub'),
    '70000000-0000-0000-0000-000000000002');


-- ===========================================================================
-- Table shape: the constraints the rest of the design leans on
-- ===========================================================================
select has_table('public', 'organisations', 'organisations exists');
select has_table('public', 'organisation_members', 'organisation_members exists');
select has_table('public', 'organisation_handoff_tokens', 'handoff tokens table exists');
select has_table('public', 'reserved_email_domains', 'reserved_email_domains exists');

-- owner_id is ON DELETE RESTRICT: deleting a user must not cascade away an
-- organisation along with its plugins and secrets.
select is(
    (select rc.delete_rule
       from information_schema.table_constraints tc
       join information_schema.referential_constraints rc
         on rc.constraint_name = tc.constraint_name
       join information_schema.key_column_usage kcu
         on kcu.constraint_name = tc.constraint_name
      where tc.table_name = 'organisations'
        and kcu.column_name = 'owner_id'
      limit 1),
    'RESTRICT',
    'organisations.owner_id is ON DELETE RESTRICT -- deleting a user must not delete an organisation'
);

-- A role belongs to at most one organisation, or org A's admins would govern org B.
select ok(
    exists (
        select 1 from pg_indexes
        where tablename = 'organisation_roles'
          and indexdef ilike '%unique%'
          and indexdef ilike '%role_id%'
    ),
    'organisation_roles.role_id is unique -- a role belongs to at most one organisation'
);

-- Consumer mailboxes must be unclaimable, or anyone registering gmail.com would
-- absorb every consumer signup.
select ok(
    exists (select 1 from public.reserved_email_domains where domain = 'gmail.com'),
    'gmail.com is a reserved email domain'
);
select ok(
    (select count(*) from public.reserved_email_domains) >= 5,
    'the reserved-domain list is seeded, not empty'
);

-- The hot path: every is_org_member() call, hence every org RLS check.
select ok(
    exists (
        select 1 from pg_indexes
        where tablename = 'organisation_members'
          and indexdef like '%org_id%'
          and indexdef like '%user_id%'
    ),
    'organisation_members is indexed on (org_id, user_id)'
);


-- ===========================================================================
-- Handoff tokens
-- ===========================================================================
-- There is no p_user_id parameter, and that absence IS the security property:
-- the subject is auth.uid() server-side, so a caller cannot mint a handoff that
-- authenticates somebody else.
select is(
    (select count(*)::int
       from information_schema.parameters
      where specific_schema = 'public'
        and parameter_name in ('p_user_id', 'p_subject', 'p_actor_id')
        and specific_name in (
            select specific_name from information_schema.routines
             where routine_schema='public'
               and routine_name='mint_organisation_handoff_token')),
    0,
    'mint_organisation_handoff_token takes NO parameter naming a subject'
);

select ok(
    has_function_privilege('authenticated',
        'public.mint_organisation_handoff_token(uuid,text,integer)', 'execute'),
    'an authenticated user may mint their own handoff token'
);
select ok(
    NOT has_function_privilege('anon',
        'public.mint_organisation_handoff_token(uuid,text,integer)', 'execute'),
    'anon may not mint one'
);
select ok(
    NOT has_function_privilege('authenticated',
        'public.consume_organisation_handoff_token(text)', 'execute'),
    'only the edge function consumes tokens -- authenticated may not'
);
select ok(
    has_function_privilege('service_role',
        'public.consume_organisation_handoff_token(text)', 'execute'),
    'service_role consumes them'
);

-- Only the hash is stored, so a leaked table does not yield usable tokens.
-- has_column IS a test function; it returns a TAP line, not a boolean, so it is
-- called directly rather than wrapped in ok().
select has_column('public', 'organisation_handoff_tokens', 'token_hash',
    'only the hash is stored, so a leaked table yields no usable tokens');
select ok(
    NOT exists (
        select 1 from information_schema.columns
        where table_name='organisation_handoff_tokens' and column_name='token'
    ),
    'the plaintext token is NOT a column'
);

-- Mint one as the member, then consume it exactly once.
select set_config('request.jwt.claims',
    '{"sub":"70000000-0000-0000-0000-000000000002","role":"authenticated"}', true);

create temporary table t_tok as
select public.mint_organisation_handoff_token(
    (select id from public.organisations where slug='pgthpub'), 'org_view', 300) as r;

select ok(
    (select (r ->> 'success')::boolean from t_tok),
    'a member can mint a handoff token for their organisation'
);

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select is(
    (select public.consume_organisation_handoff_token((select r ->> 'token' from t_tok))
            ->> 'user_id'),
    '70000000-0000-0000-0000-000000000002',
    'consuming it yields the minting user'
);
select is(
    (select public.consume_organisation_handoff_token((select r ->> 'token' from t_tok))
            ->> 'error'),
    'Token is invalid, expired or already used',
    'the SAME token cannot be consumed twice'
);
select is(
    (select public.consume_organisation_handoff_token('not-a-real-token') ->> 'error'),
    'Token is invalid, expired or already used',
    'an unknown token reports exactly what a used one does -- no oracle'
);
select is(
    (select public.consume_organisation_handoff_token(null) ->> 'error'),
    'Token is invalid, expired or already used',
    'a null token reports the same'
);

-- An expired token is refused even though the row exists and is unconsumed.
select set_config('request.jwt.claims',
    '{"sub":"70000000-0000-0000-0000-000000000002","role":"authenticated"}', true);
create temporary table t_tok2 as
select public.mint_organisation_handoff_token(
    (select id from public.organisations where slug='pgthpub'), 'org_view', 300) as r;
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

update public.organisation_handoff_tokens
   set expires_at = now() - interval '1 minute'
 where consumed_at is null;

select is(
    (select public.consume_organisation_handoff_token((select r ->> 'token' from t_tok2))
            ->> 'error'),
    'Token is invalid, expired or already used',
    'an expired but unconsumed token is refused'
);

-- A non-member cannot mint, for a private OR a public organisation.
--
-- The public arm used to be allowed, on the rationale that "browse a public org, then request to
-- join" needed it. That flow does not exist - the page such a token opens refuses a non-member
-- twice, on the live is_org_member probe and again in get_organisation_detail - so the arm
-- granted a token that could only ever 404.
select set_config('request.jwt.claims',
    '{"sub":"70000000-0000-0000-0000-000000000003","role":"authenticated"}', true);
select ok(
    NOT (select public.mint_organisation_handoff_token(
        (select id from public.organisations where slug='pgthpriv'), 'org_view', 300)
        ->> 'success')::boolean,
    'a non-member cannot mint a token for a private organisation'
);
select ok(
    NOT (select public.mint_organisation_handoff_token(
        (select id from public.organisations where slug='pgthpub'), 'org_view', 300)
        ->> 'success')::boolean,
    'nor for a PUBLIC one -- the page would refuse them anyway'
);
select set_config('request.jwt.claims', '{"role":"service_role"}', true);


-- ===========================================================================
-- JWT claims
-- ===========================================================================
-- Omitting this grant breaks EVERY login, because GoTrue calls the hook as
-- supabase_auth_admin and a permission error there fails the whole token issue.
select ok(
    has_function_privilege('supabase_auth_admin',
        'public.get_user_orgs_for_hook(uuid)', 'execute'),
    'supabase_auth_admin may call the claims hook -- without this, every login breaks'
);
select ok(
    NOT has_function_privilege('authenticated',
        'public.get_user_orgs_for_hook(uuid)', 'execute'),
    'authenticated may not call it directly'
);

-- SLUGS, not ids. A token carries these to every request, and a slug is both
-- shorter and the thing a policy is actually written against.
select ok(
    public.get_user_orgs_for_hook('70000000-0000-0000-0000-000000000002') -> 'orgs'
        ? 'pgthpub',
    'the hook lists the organisation by slug'
);
select ok(
    NOT (public.get_user_orgs_for_hook('70000000-0000-0000-0000-000000000002') -> 'orgs'
        ? 'pgthpriv'),
    'and does not list one the user does not belong to'
);
select ok(
    public.get_user_orgs_for_hook('70000000-0000-0000-0000-000000000001') -> 'org_admin'
        ? 'pgthpub',
    'the owner is listed as an admin of their organisation'
);
select ok(
    NOT (public.get_user_orgs_for_hook('70000000-0000-0000-0000-000000000002') -> 'org_admin'
        ? 'pgthpub'),
    'an ordinary member is not'
);
select ok(
    public.get_user_orgs_for_hook('70000000-0000-0000-0000-000000000003') -> 'orgs'
        = '[]'::jsonb,
    'a user in no organisation gets an empty array, not null'
);

-- The hook must never throw: an exception there is a failed login, not a
-- missing claim.
select lives_ok(
    $$ select public.get_user_orgs_for_hook('00000000-0000-0000-0000-0000000000ff') $$,
    'the hook tolerates an unknown user rather than throwing'
);
select ok(
    public.get_user_orgs_for_hook('00000000-0000-0000-0000-0000000000ff') -> 'orgs'
        = '[]'::jsonb,
    'and degrades to empty claims rather than a missing key'
);


-- ===========================================================================
-- Discovery
-- ===========================================================================
select ok(
    exists (
        select 1 from jsonb_array_elements(
            public.search_organisations('Public Handoff', 20, 0,
                '70000000-0000-0000-0000-000000000003') -> 'data') e
        where e ->> 'slug' = 'pgthpub'
    ),
    'a public organisation is discoverable by an outsider'
);
select ok(
    NOT exists (
        select 1 from jsonb_array_elements(
            public.search_organisations('Private Handoff', 20, 0,
                '70000000-0000-0000-0000-000000000003') -> 'data') e
        where e ->> 'slug' = 'pgthpriv'
    ),
    'a PRIVATE organisation is not discoverable, even by exact name'
);
select ok(
    NOT exists (
        select 1 from jsonb_array_elements(
            public.search_organisations('pgthpriv', 20, 0,
                '70000000-0000-0000-0000-000000000003') -> 'data') e
        where e ->> 'slug' = 'pgthpriv'
    ),
    'nor by exact slug -- search is not a slug oracle'
);
select is(
    (select e ->> 'available_action'
       from jsonb_array_elements(
            public.search_organisations('Public Handoff', 20, 0,
                '70000000-0000-0000-0000-000000000003') -> 'data') e
      where e ->> 'slug' = 'pgthpub'),
    'join',
    'discovery carries the action the server decided, not one the client infers'
);
select is(
    (select e ->> 'available_action'
       from jsonb_array_elements(
            public.search_organisations('Public Handoff', 20, 0,
                '70000000-0000-0000-0000-000000000002') -> 'data') e
      where e ->> 'slug' = 'pgthpub'),
    'member',
    'an existing member sees member, not join'
);

-- ===========================================================================
-- verification_token is not readable by authenticated
--
-- The SELECT policy is `is_org_member(org_id) OR verified`, so a verified row is
-- deliberately visible to any authenticated user. RLS is row-level, so without a
-- COLUMN-level revoke the token rode along on every one of those rows - while two
-- comments claimed it was returned solely by the org-admin RPC.
-- ===========================================================================
select ok(
    NOT has_column_privilege('authenticated',
        'public.organisation_domains', 'verification_token', 'select'),
    'authenticated cannot read verification_token'
);
select ok(
    has_column_privilege('authenticated', 'public.organisation_domains', 'domain', 'select'),
    'but the row itself stays discoverable -- verified domains are discovery metadata'
);
select ok(
    has_column_privilege('service_role',
        'public.organisation_domains', 'verification_token', 'select'),
    'and the admin path through the SECURITY DEFINER RPC is unaffected'
);

-- ===========================================================================
-- Guard 1 re-validates a role's EXISTING permissions on mapping
--
-- Guard 2 fires on role_permissions, so alone it only constrains permissions
-- attached AFTER the mapping. "Create a plain role, grant it role.assign, then
-- map it in" walked past both.
-- ===========================================================================
insert into public.roles (name, description, is_system)
values ('pgt_preloaded', 'a role that already holds a forbidden permission', false)
on conflict (name) do nothing;

insert into public.role_permissions (role_id, permission_id)
select r.id, p.id
  from public.roles r, public.permissions p
 where r.name = 'pgt_preloaded' and p.name = 'role.assign'
on conflict do nothing;

select throws_ok(
    $$ insert into public.organisation_roles (org_id, role_id, kind)
       values ((select id from public.organisations where slug='pgthpub'),
               (select id from public.roles where name='pgt_preloaded'),
               'custom') $$,
    'P0001',
    null,
    'a role already holding role.assign cannot be mapped into an organisation'
);

-- ===========================================================================
-- Table grants are the posture the file claims
--
-- Supabase's default privileges GRANT ALL ON TABLES to anon and authenticated for
-- schema public, so every CREATE TABLE here starts fully granted to both. Nothing
-- asserted any table privilege before, which is why the gap survived four reviews.
-- ===========================================================================
select ok(
    NOT has_table_privilege('anon', 'public.organisations', 'SELECT')
    AND NOT has_table_privilege('anon', 'public.organisation_members', 'SELECT')
    AND NOT has_table_privilege('anon', 'public.organisation_invites', 'SELECT'),
    'anon holds nothing on the organisation tables'
);
select ok(
    NOT has_table_privilege('authenticated', 'public.organisations', 'INSERT')
    AND NOT has_table_privilege('authenticated', 'public.organisations', 'UPDATE')
    AND NOT has_table_privilege('authenticated', 'public.organisations', 'DELETE'),
    'authenticated cannot write organisations -- held off by the GRANT, not only by RLS'
);
select ok(
    NOT has_table_privilege('authenticated', 'public.organisation_members', 'UPDATE')
    AND NOT has_table_privilege('authenticated', 'public.organisation_roles', 'UPDATE')
    AND NOT has_table_privilege('authenticated', 'public.organisation_requests', 'DELETE'),
    'nor the other membership tables'
);

-- The two credential tables get NOTHING back. token_hash is a SHA-256 of a live
-- invite token, and the invites migration claims the table "is never selected
-- directly by the desktop app" - that claim was false until this revoke.
select ok(
    NOT has_table_privilege('authenticated', 'public.organisation_invites', 'SELECT'),
    'authenticated cannot select organisation_invites, so token_hash is unreachable'
);
select ok(
    NOT has_table_privilege('authenticated', 'public.organisation_handoff_tokens', 'SELECT'),
    'nor organisation_handoff_tokens'
);

-- ...while the reads the panel actually needs still work.
select ok(
    has_table_privilege('authenticated', 'public.organisations', 'SELECT')
    AND has_table_privilege('authenticated', 'public.organisation_members', 'SELECT'),
    'authenticated keeps the SELECTs the RLS policies are written for'
);
select ok(
    has_table_privilege('service_role', 'public.organisation_invites', 'SELECT'),
    'and service_role is unaffected'
);

select * from finish();
rollback;
