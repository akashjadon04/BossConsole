-- pgTAP tests for get_organisation_detail (migration 20260804000000).
-- Run with: supabase test db
--
-- The load-bearing assertions are the negative ones: a non-member gets the same
-- "Organisation not found" a missing org gets, and a plain member's response
-- must not merely null the admin-only settings but omit the keys entirely --
-- a nulled key still tells the reader the setting exists and is unset.

begin;
select plan(20);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('20000000-0000-0000-0000-000000000001', 'detowner@pgtap.test',   now()),
    ('20000000-0000-0000-0000-000000000002', 'detmember@pgtap.test',  now()),
    ('20000000-0000-0000-0000-000000000003', 'detoutsider@pgtap.test', now());

select public.create_organisation_internal(
    p_slug=>'pgtdet', p_name=>'PGTap Detail',
    p_description=>'A fixture organisation',
    p_owner_id=>'20000000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'open');

-- Act as the edge function does: service_role naming an actor.
select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select public.join_organisation(
    (select id from public.organisations where slug='pgtdet'),
    '20000000-0000-0000-0000-000000000002');


-- ===========================================================================
-- Authentication and existence
-- ===========================================================================
select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'), null) ->> 'error'),
    'Not authenticated',
    'service_role without a named actor resolves to no actor'
);

select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '20000000-0000-0000-0000-000000000003') ->> 'error'),
    'Organisation not found',
    'a non-member is refused'
);

select is(
    (select public.get_organisation_detail(
        '00000000-0000-0000-0000-0000000000ff',
        '20000000-0000-0000-0000-000000000001') ->> 'error'),
    'Organisation not found',
    'a missing organisation reports exactly what a non-member does (no existence oracle)'
);

select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '00000000-0000-0000-0000-0000000000fe') ->> 'error'),
    'Not authenticated',
    'an actor id that is not a real user is not honoured'
);


-- ===========================================================================
-- Member projection
-- ===========================================================================
select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '20000000-0000-0000-0000-000000000002') -> 'data' ->> 'slug'),
    'pgtdet',
    'a member can read the organisation'
);

select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '20000000-0000-0000-0000-000000000002') -> 'data' ->> 'owner_email'),
    'detowner@pgtap.test',
    'the owner email is member-visible'
);

select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '20000000-0000-0000-0000-000000000002') -> 'data' ->> 'member_count'),
    '2',
    'member_count counts active members'
);

select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '20000000-0000-0000-0000-000000000002') -> 'data' ->> 'is_admin'),
    'false',
    'a plain member is not an admin'
);

select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '20000000-0000-0000-0000-000000000002') -> 'data' ->> 'is_owner'),
    'false',
    'a plain member is not the owner'
);

-- The whole point of the admin gate: absent keys, not null keys.
select ok(
    NOT ((select public.get_organisation_detail(
            (select id from public.organisations where slug='pgtdet'),
            '20000000-0000-0000-0000-000000000002') -> 'data') ? 'publish_policy'),
    'publish_policy is ABSENT from a member response, not null'
);
select ok(
    NOT ((select public.get_organisation_detail(
            (select id from public.organisations where slug='pgtdet'),
            '20000000-0000-0000-0000-000000000002') -> 'data') ? 'publish_role_id'),
    'publish_role_id is absent from a member response'
);
select ok(
    NOT ((select public.get_organisation_detail(
            (select id from public.organisations where slug='pgtdet'),
            '20000000-0000-0000-0000-000000000002') -> 'data') ? 'auto_assign_member_role'),
    'auto_assign_member_role is absent from a member response'
);
select ok(
    NOT ((select public.get_organisation_detail(
            (select id from public.organisations where slug='pgtdet'),
            '20000000-0000-0000-0000-000000000002') -> 'data') ? 'max_custom_roles'),
    'max_custom_roles is absent from a member response'
);
select ok(
    NOT ((select public.get_organisation_detail(
            (select id from public.organisations where slug='pgtdet'),
            '20000000-0000-0000-0000-000000000002') -> 'data') ? 'plugin_count'),
    'plugin_count is absent from a member response'
);


-- ===========================================================================
-- Admin projection
-- ===========================================================================
select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '20000000-0000-0000-0000-000000000001') -> 'data' ->> 'is_admin'),
    'true',
    'the owner is an admin'
);

select ok(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '20000000-0000-0000-0000-000000000001') -> 'data') ? 'publish_policy',
    'publish_policy is present for an admin'
);

select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '20000000-0000-0000-0000-000000000001') -> 'data' ->> 'custom_role_count'),
    '0',
    'a fresh organisation has no custom roles'
);

-- A custom role must move that counter, or the "create role" button would
-- happily blow past max_custom_roles.
select public.create_organisation_role(
    (select id from public.organisations where slug='pgtdet'),
    'reviewer', 'Reviews things', '20000000-0000-0000-0000-000000000001');

select is(
    (select public.get_organisation_detail(
        (select id from public.organisations where slug='pgtdet'),
        '20000000-0000-0000-0000-000000000001') -> 'data' ->> 'custom_role_count'),
    '1',
    'creating a custom role increments custom_role_count'
);


-- ===========================================================================
-- Grants
-- ===========================================================================
select ok(
    has_function_privilege('authenticated',
        'public.get_organisation_detail(uuid,uuid)', 'execute'),
    'authenticated may call it (the desktop plugin reads it directly)'
);

select ok(
    NOT has_function_privilege('anon',
        'public.get_organisation_detail(uuid,uuid)', 'execute'),
    'anon may not'
);

select * from finish();
rollback;
