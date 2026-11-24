-- pgTAP tests for list_org_plugins (20260813000000).
-- Run with: supabase test db
--
-- Two properties, and the second is the one that would be easy to lose in a refactor:
-- enumeration requires membership, and each ROW is still filtered by user_can_view_plugin_row.
-- Being in an organisation is not permission to see everything attached to it - `unlisted` means
-- unlisted to members too - so a listing that filtered only on org_id would show a member more
-- than the store catalogue ever would.

begin;
select plan(15);

insert into auth.users (id, email, email_confirmed_at) values
    ('f0000000-0000-4000-8000-000000000001', 'admin@lop.test',    now()),
    ('f0000000-0000-4000-8000-000000000002', 'member@lop.test',   now()),
    ('f0000000-0000-4000-8000-000000000003', 'outsider@lop.test', now()),
    ('f0000000-0000-4000-8000-000000000004', 'other@lop.test',    now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select ok(
    (public.create_organisation_internal(
        p_slug => 'lopco', p_name => 'Lop Co', p_description => null,
        p_owner_id => 'f0000000-0000-4000-8000-000000000001',
        p_domain => null, p_visibility => 'private',
        p_join_policy => 'invite_only') ->> 'success')::boolean,
    'the organisation exists'
);

select ok(
    (public.create_organisation_internal(
        p_slug => 'otherco', p_name => 'Other Co', p_description => null,
        p_owner_id => 'f0000000-0000-4000-8000-000000000004',
        p_domain => null, p_visibility => 'private',
        p_join_policy => 'invite_only') ->> 'success')::boolean,
    'and a second one, to prove the org filter'
);

-- A third organisation, with nothing in it, for the empty-list assertion. Created without its own
-- ok() because the assertion that reads it fails loudly if this did not happen: a missing org
-- makes user_is_org_member false and the call answers Permission denied rather than an array.
insert into auth.users (id, email, email_confirmed_at)
values ('f0000000-0000-4000-8000-000000000005', 'empty@lop.test', now());

select public.create_organisation_internal(
    p_slug => 'emptyco', p_name => 'Empty Co', p_description => null,
    p_owner_id => 'f0000000-0000-4000-8000-000000000005',
    p_domain => null, p_visibility => 'private',
    p_join_policy => 'invite_only');

create temporary table t as
select (select id from public.organisations where slug = 'lopco')   as org,
       (select id from public.organisations where slug = 'otherco') as other,
       (select id from public.organisations where slug = 'emptyco') as empty;

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
values ((select org from t), 'f0000000-0000-4000-8000-000000000002', 'active', now(), 'admin');

-- Four plugins on lopco covering every arm of user_can_view_plugin_row that a plain member meets,
-- plus one on otherco. Authored by the OUTSIDER so the author arm never fires for the readers
-- under test - it would otherwise mask the visibility filter entirely.
insert into public.plugins (author_id, author_name, plugin_id, display_name, type, api_version,
                            published, visibility, org_id)
values ('f0000000-0000-4000-8000-000000000003', 'A', 'test.lop.public',   'B Public',   'panel', '1.0', true,  'public',   (select org from t)),
       ('f0000000-0000-4000-8000-000000000003', 'A', 'test.lop.org',      'A Org',      'panel', '1.0', true,  'org',      (select org from t)),
       ('f0000000-0000-4000-8000-000000000003', 'A', 'test.lop.unlisted', 'C Unlisted', 'panel', '1.0', true,  'unlisted', (select org from t)),
       ('f0000000-0000-4000-8000-000000000003', 'A', 'test.lop.draft',    'D Draft',    'panel', '1.0', false, 'public',   (select org from t)),
       ('f0000000-0000-4000-8000-000000000003', 'A', 'test.other.public', 'E Other',    'panel', '1.0', true,  'public',   (select other from t));


-- ===========================================================================
-- Who may enumerate
-- ===========================================================================
select is(
    public.list_org_plugins((select org from t), 'f0000000-0000-4000-8000-000000000003') ->> 'error',
    'Permission denied',
    'somebody outside the organisation may not enumerate it'
);

select is(
    public.list_org_plugins((select org from t), null) ->> 'error',
    'Not authenticated',
    'and neither may an unidentified caller'
);

select is(
    (public.list_org_plugins((select org from t), 'f0000000-0000-4000-8000-000000000002')
        ->> 'success')::boolean,
    true,
    'an active member may'
);


-- ===========================================================================
-- Each row is still filtered for the reader
-- ===========================================================================
create temporary table member_rows as
select jsonb_array_elements(
           public.list_org_plugins((select org from t), 'f0000000-0000-4000-8000-000000000002')
               -> 'data') ->> 'plugin_id' as plugin_id;

select is(
    (select count(*) from member_rows where plugin_id = 'test.lop.public'),
    1::bigint,
    'a member sees a public plugin'
);

select is(
    (select count(*) from member_rows where plugin_id = 'test.lop.org'),
    1::bigint,
    'and an org-visibility one, because they are a member'
);

select is(
    (select count(*) from member_rows where plugin_id = 'test.lop.unlisted'),
    0::bigint,
    'but NOT an unlisted one - unlisted is install-by-link, not member-visible'
);

select is(
    (select count(*) from member_rows where plugin_id = 'test.lop.draft'),
    0::bigint,
    'and not an unpublished draft they did not write'
);

select is(
    (select count(*) from member_rows where plugin_id = 'test.other.public'),
    0::bigint,
    'another organisation''s plugin is not in this list'
);

-- The admin arm, which is what makes the unlisted assertion above a filter rather than a plugin
-- that simply never appears.
select is(
    (select count(*)
       from jsonb_array_elements(
                public.list_org_plugins((select org from t), 'f0000000-0000-4000-8000-000000000001')
                    -> 'data') e
      where e ->> 'plugin_id' = 'test.lop.unlisted'),
    1::bigint,
    'an organisation admin does see the unlisted one'
);


-- ===========================================================================
-- Shape
-- ===========================================================================
-- jsonb_agg over no rows is NULL, not '[]', so without the COALESCE this key would be
-- absent-shaped for exactly the organisations most likely to be reading the page for the first
-- time.
select is(
    public.list_org_plugins((select empty from t), 'f0000000-0000-4000-8000-000000000005')
        -> 'data',
    '[]'::jsonb,
    'an organisation with nothing visible returns an empty array, never null'
);

-- Ordered so the page does not have to be, and so two reads of an unchanged organisation agree.
-- Named A/B/C deliberately: alphabetical order differs from insertion order here, so an ORDER BY
-- that was dropped would show up.
--
-- 'D Draft' is absent on purpose and is the sharper half of this assertion. An ORG ADMIN does not
-- see an unpublished plugin they did not write: user_can_view_plugin_row's admin arm is
-- is_user_admin (the GLOBAL admin), and the org-admin arm covers only 'unlisted'. So the page
-- shows this organisation's own administrator less than they might expect, and that is the
-- predicate's answer rather than this function's - worth pinning here so a change to either is
-- a visible decision.
select is(
    (select array_agg(e ->> 'display_name')
       from jsonb_array_elements(
                public.list_org_plugins((select org from t), 'f0000000-0000-4000-8000-000000000001')
                    -> 'data') e),
    array['A Org', 'B Public', 'C Unlisted'],
    'rows come back ordered by display name, and a draft is not among them even for an org admin'
);

-- ===========================================================================
-- The envelope key
-- ===========================================================================
-- Pinned because getting it wrong cost a whole feature and produced no error anyone could act on.
-- This function first returned its rows under `plugins`; every other organisation RPC returns
-- `data`, and BOTH shared decoders key on that - utils/org-rpc.ts and the desktop plugin's
-- RpcEnvelope. The edge function had been written against the odd key so never noticed, and the
-- plugin reported "Something went wrong. Please try again." for a call that had succeeded.
select ok(
    public.list_org_plugins((select org from t), 'f0000000-0000-4000-8000-000000000001') ? 'data',
    'the rows come back under `data`, the key every org RPC uses'
);

select ok(
    NOT (public.list_org_plugins((select org from t), 'f0000000-0000-4000-8000-000000000001') ? 'plugins'),
    'and not under `plugins`, which no shared decoder reads'
);

select * from finish();
rollback;
