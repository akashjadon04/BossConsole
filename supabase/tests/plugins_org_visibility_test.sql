-- pgTAP tests for plugin organisation ownership and visibility
-- (migration 20260803000000).
-- Run with: supabase test db
--
-- Leads with the BACKFILL and BACKWARD-COMPATIBILITY assertions, because this
-- migration touches the live plugin store: if it changes what an anonymous
-- browser sees, the store appears to empty out. Then the three visibility levels,
-- then the leaks that are easy to forget -- get_plugin_versions (jar_path!) and
-- get_popular_tags.

begin;
select plan(34);

-- ---------------------------------------------------------------------------
-- Fixtures
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('70000000-0000-0000-0000-000000000001', 'pubowner@pgtap.test',  now()),
    ('70000000-0000-0000-0000-000000000002', 'pubmember@pgtap.test', now()),
    ('70000000-0000-0000-0000-000000000003', 'puboutside@pgtap.test',now()),
    ('70000000-0000-0000-0000-000000000004', 'pubadmin@pgtap.test',  now());

insert into public.user_roles (user_id, role_id)
select '70000000-0000-0000-0000-000000000004', id from public.roles where name='admin'
on conflict do nothing;

select public.create_organisation_internal(
    p_slug=>'pgtplug', p_name=>'PGTap Plugins',
    p_owner_id=>'70000000-0000-0000-0000-000000000001',
    p_visibility=>'private', p_join_policy=>'invite_only');

insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
select id, '70000000-0000-0000-0000-000000000002', 'active', now(), 'admin'
from public.organisations where slug='pgtplug';

create temporary table t_p (k text primary key, v uuid);
insert into t_p select 'org', id from public.organisations where slug='pgtplug';
-- The boss organisation, created HERE rather than assumed.
--
-- `supabase db start` migrates a database with zero auth.users, and seed.sql creates none, so
-- 20260801070000 SECTION 1 always takes its "no users yet" branch: the boss org does not exist
-- in ANY ci run. The assertion below compared plugins.org_id against this row, so with no row
-- both sides were NULL and pgTAP's is() passes NULL = NULL - the plugins_default_org trigger,
-- which is what keeps the store's publish path working now that org_id exists, was never
-- actually verified.
select public.create_organisation_internal(
    p_slug=>'boss', p_name=>'BOSS',
    p_owner_id=>'70000000-0000-0000-0000-000000000001',
    p_visibility=>'public', p_join_policy=>'open',
    p_is_system=>true,
    p_admin_role_name=>'boss_org_admin',
    p_user_role_name=>'boss_org_user',
    p_auto_assign_member_role=>false);

insert into t_p select 'bossorg', id from public.organisations where slug='boss';

-- The guard that stops this degrading back to NULL = NULL.
select isnt((select v from t_p where k='bossorg'), null,
    'FIXTURE: the boss organisation exists, so the trigger assertion below is not vacuous');

-- One plugin at each visibility level, all owned by the pgtplug organisation and
-- authored by the org owner.
insert into public.plugins (plugin_id, display_name, author_id, author_name, type, api_version, published, org_id, visibility)
values
  ('test.pub',   'Public Plugin',   '70000000-0000-0000-0000-000000000001', 'owner', 'panel', '1.0', true,
   (select v from t_p where k='org'), 'public'),
  ('test.org',   'Org Plugin',      '70000000-0000-0000-0000-000000000001', 'owner', 'panel', '1.0', true,
   (select v from t_p where k='org'), 'org'),
  ('test.unlst', 'Unlisted Plugin', '70000000-0000-0000-0000-000000000001', 'owner', 'panel', '1.0', true,
   (select v from t_p where k='org'), 'unlisted');

insert into public.plugin_versions (plugin_id, version, jar_path, jar_size, sha256)
select p.id, '1.0.0', 'plugin-jars/' || p.plugin_id || '-1.0.0.jar', 1024, repeat('a', 64)
from public.plugins p where p.plugin_id in ('test.pub','test.org','test.unlst');

insert into public.plugin_tags (plugin_id, tag)
select p.id, 'secret-project-name'
from public.plugins p where p.plugin_id = 'test.org';
insert into public.plugin_tags (plugin_id, tag)
select p.id, 'public-tag'
from public.plugins p where p.plugin_id = 'test.pub';


-- ===========================================================================
-- BACKFILL and BACKWARD COMPATIBILITY
-- ===========================================================================
select is(
    (select count(*)::int from public.plugins where visibility is null),
    0, 'BACKFILL: no plugin is left with a NULL visibility');
select is(
    (select count(*)::int from public.plugins where org_id is null),
    0, 'BACKFILL: every plugin has an owning organisation');

-- The BEFORE INSERT trigger: the edge function inserts without org_id.
insert into public.plugins (plugin_id, display_name, author_id, author_name, type, api_version)
values ('test.noorg', 'No Org Given', '70000000-0000-0000-0000-000000000001', 'owner', 'panel', '1.0');
select isnt(
    (select org_id from public.plugins where plugin_id='test.noorg'), null,
    'TRIGGER: an insert without org_id gets one');
select is(
    (select org_id from public.plugins where plugin_id='test.noorg'),
    (select v from t_p where k='bossorg'),
    'TRIGGER: and it is the boss organisation (the edge function inserts this way)');
select is(
    (select visibility from public.plugins where plugin_id='test.noorg'),
    'public',
    'DEFAULT: an insert without visibility defaults to public, so new publishes stay visible');

select throws_ok(
    $$ update public.plugins set visibility='nonsense' where plugin_id='test.pub' $$,
    '23514', NULL,
    'plugins_visibility_check rejects an unknown visibility');


-- ===========================================================================
-- ANONYMOUS: the public store must look exactly as it did
-- ===========================================================================
select set_config('request.jwt.claims', '', true);

select ok(
    public.user_can_view_plugin_row(NULL, 'public', (select v from t_p where k='org'),
        '70000000-0000-0000-0000-000000000001', true),
    'ANON: a public published plugin is visible with no session at all');
select ok(
    not public.user_can_view_plugin_row(NULL, 'org', (select v from t_p where k='org'),
        '70000000-0000-0000-0000-000000000001', true),
    'ANON: an org plugin is not visible anonymously');
select ok(
    not public.user_can_view_plugin_row(NULL, 'unlisted', (select v from t_p where k='org'),
        '70000000-0000-0000-0000-000000000001', true),
    'ANON: an unlisted plugin is not visible anonymously');
select ok(
    not public.user_can_view_plugin_row(NULL, 'public', (select v from t_p where k='org'),
        '70000000-0000-0000-0000-000000000001', false),
    'ANON: an UNPUBLISHED public plugin is still hidden');

select is(
    (select count(*)::int from public.get_plugin_with_stats('test.pub')),
    1, 'ANON: get_plugin_with_stats returns the public plugin');
select is(
    (select count(*)::int from public.get_plugin_with_stats('test.org')),
    0, 'ANON: get_plugin_with_stats returns NOTHING for the org plugin (404, not 403)');

-- The jar_path leak.
select is(
    (select count(*)::int from public.get_plugin_versions('test.org')),
    0,
    'LEAK CHECK: get_plugin_versions returns no rows for an org plugin -- these carry jar_path and sha256');
select is(
    (select count(*)::int from public.get_plugin_versions('test.pub')),
    1, 'get_plugin_versions still works for a public plugin');

-- The tag-cloud leak.
select is(
    (select count(*)::int from public.get_popular_tags(50) where tag='secret-project-name'),
    0,
    'LEAK CHECK: get_popular_tags excludes an org plugin''s tags -- they would leak internal project names');
select is(
    (select count(*)::int from public.get_popular_tags(50) where tag='public-tag'),
    1, 'get_popular_tags still lists public plugin tags');


-- ===========================================================================
-- Per-viewer visibility
-- ===========================================================================
-- An OUTSIDER.
select ok(
    not public.user_can_view_plugin_row('70000000-0000-0000-0000-000000000003', 'org',
        (select v from t_p where k='org'), '70000000-0000-0000-0000-000000000001', true),
    'OUTSIDER: cannot see an org plugin');
select is(
    (select total_count from public.search_plugins_for_viewer('70000000-0000-0000-0000-000000000003', 'Org Plugin')),
    0::bigint, 'OUTSIDER: search_plugins_for_viewer excludes the org plugin');

-- A MEMBER.
select ok(
    public.user_can_view_plugin_row('70000000-0000-0000-0000-000000000002', 'org',
        (select v from t_p where k='org'), '70000000-0000-0000-0000-000000000001', true),
    'MEMBER: can see an org plugin');
select is(
    (select total_count from public.search_plugins_for_viewer('70000000-0000-0000-0000-000000000002', 'Org Plugin')),
    1::bigint, 'MEMBER: search_plugins_for_viewer includes the org plugin');
select ok(
    not public.user_can_view_plugin_row('70000000-0000-0000-0000-000000000002', 'unlisted',
        (select v from t_p where k='org'), '70000000-0000-0000-0000-000000000001', true),
    'MEMBER: a plain member CANNOT see an unlisted plugin -- unlisted is install-by-link, not org-wide');

-- The AUTHOR and the ORGANISATION ADMIN.
select ok(
    public.user_can_view_plugin_row('70000000-0000-0000-0000-000000000001', 'unlisted',
        (select v from t_p where k='org'), '70000000-0000-0000-0000-000000000001', true),
    'AUTHOR: can see their own unlisted plugin');
select ok(
    public.user_can_view_plugin_row('70000000-0000-0000-0000-000000000001', 'public',
        (select v from t_p where k='org'), '70000000-0000-0000-0000-000000000001', false),
    'AUTHOR: can see their own UNPUBLISHED plugin');
select ok(
    public.user_can_view_plugin_row('70000000-0000-0000-0000-000000000004', 'unlisted',
        (select v from t_p where k='org'), '70000000-0000-0000-0000-000000000001', true),
    'GLOBAL ADMIN: can see everything');

-- The service-role probe the download route must call.
select ok(
    public.user_can_view_plugin('70000000-0000-0000-0000-000000000002',
        (select id from public.plugins where plugin_id='test.org')),
    'user_can_view_plugin: true for a member');
select ok(
    not public.user_can_view_plugin('70000000-0000-0000-0000-000000000003',
        (select id from public.plugins where plugin_id='test.org')),
    'user_can_view_plugin: false for an outsider -- the download route MUST 404 on this');
select ok(
    not public.user_can_view_plugin(NULL,
        (select id from public.plugins where plugin_id='test.org')),
    'user_can_view_plugin: accepts NULL for anonymous and answers false');


-- ===========================================================================
-- Exposure of the service-role-only probes
-- ===========================================================================
select ok(
    not has_function_privilege('authenticated', 'public.user_can_view_plugin(uuid,uuid)', 'execute'),
    'user_can_view_plugin is NOT executable by authenticated -- it takes an arbitrary subject');
select ok(
    not has_function_privilege('authenticated',
        'public.search_plugins_for_viewer(uuid,text,text,text[],numeric,boolean,integer,integer,text)', 'execute'),
    'search_plugins_for_viewer is NOT executable by authenticated -- it would allow browsing as anyone');
select ok(
    has_function_privilege('anon', 'public.get_plugin_with_stats(text)', 'execute'),
    'get_plugin_with_stats IS still executable by anon after the DROP -- anonymous store browsing depends on it');
select ok(
    has_function_privilege('anon',
        'public.search_plugins(text,text,text[],numeric,boolean,integer,integer,text)', 'execute'),
    'search_plugins IS still executable by anon');

-- No accidental overload: adding a parameter to search_plugins instead of adding
-- a separate _for_viewer name would make PostgREST fail every call.
select is(
    (select count(*)::int from pg_proc p join pg_namespace n on n.oid=p.pronamespace
      where n.nspname='public' and p.proname='search_plugins'),
    1, 'search_plugins has exactly ONE overload (a second would break PostgREST resolution)');
select is(
    (select count(*)::int from pg_proc p join pg_namespace n on n.oid=p.pronamespace
      where n.nspname='public' and p.proname='get_plugin_versions'),
    1, 'get_plugin_versions has exactly ONE overload');

select * from finish();
rollback;
