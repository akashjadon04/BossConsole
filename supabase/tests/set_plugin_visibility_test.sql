-- pgTAP tests for set_plugin_visibility (20260813000000).
-- Run with: supabase test db
--
-- The RLS UPDATE policy on `plugins` already states this rule, and it has never been evaluated:
-- every client reaches that table through the service-role plugin-store function, which bypasses
-- RLS entirely. So this function is where the rule is actually enforced, and these are the
-- assertions that would otherwise be resting on a policy nothing runs.

begin;
select plan(17);

insert into auth.users (id, email, email_confirmed_at) values
    ('e0000000-0000-4000-8000-000000000001', 'owner@vis.test',   now()),
    ('e0000000-0000-4000-8000-000000000002', 'member@vis.test',  now()),
    ('e0000000-0000-4000-8000-000000000003', 'outsider@vis.test', now()),
    ('e0000000-0000-4000-8000-000000000004', 'author@vis.test',  now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select ok(
    (public.create_organisation_internal(
        p_slug => 'visco', p_name => 'Vis Co', p_description => null,
        p_owner_id => 'e0000000-0000-4000-8000-000000000001',
        p_domain => null, p_visibility => 'private',
        p_join_policy => 'invite_only') ->> 'success')::boolean,
    'the owning organisation exists'
);

create temporary table t as
select (select id from public.organisations where slug = 'visco') as org;

-- A plain member, and the AUTHOR as a separate person from the admin. That separation is the
-- point of one of the assertions below.
insert into public.organisation_members (org_id, user_id, status, joined_at, join_source)
values ((select org from t), 'e0000000-0000-4000-8000-000000000002', 'active', now(), 'admin'),
       ((select org from t), 'e0000000-0000-4000-8000-000000000004', 'active', now(), 'admin');

insert into public.plugins (author_id, author_name, plugin_id, display_name, type, api_version,
                            published, visibility, org_id)
values ('e0000000-0000-4000-8000-000000000004', 'Author', 'test.vis.plugin', 'Vis Plugin',
        'panel', '1.0', true, 'public', (select org from t));

create temporary table tp as
select id from public.plugins where plugin_id = 'test.vis.plugin';


-- ===========================================================================
-- Who may
-- ===========================================================================
select is(
    public.set_plugin_visibility(
        (select id from tp), 'org', 'e0000000-0000-4000-8000-000000000001') ->> 'success',
    'true',
    'an admin of the owning organisation may set it'
);

select is(
    (select visibility from public.plugins where plugin_id = 'test.vis.plugin'),
    'org',
    'and the row is actually changed'
);

select is(
    public.set_plugin_visibility(
        (select id from tp), 'public', 'e0000000-0000-4000-8000-000000000002') ->> 'error',
    'Permission denied',
    'a plain member may not'
);

select is(
    public.set_plugin_visibility(
        (select id from tp), 'public', 'e0000000-0000-4000-8000-000000000003') ->> 'error',
    'Permission denied',
    'somebody outside the organisation may not'
);

-- The deliberate exclusion. plugins.author_id is whoever ran the publish - for most of this store
-- one person - and 20260812000000 moved five plugins to an organisation without touching it.
-- Authorship records who pushed the button; the organisation records who is answerable.
select is(
    public.set_plugin_visibility(
        (select id from tp), 'public', 'e0000000-0000-4000-8000-000000000004') ->> 'error',
    'Permission denied',
    'the AUTHOR may not, unless they are also an organisation admin'
);

select is(
    (select visibility from public.plugins where plugin_id = 'test.vis.plugin'),
    'org',
    'and none of those refusals changed the row'
);


-- ===========================================================================
-- What it accepts
-- ===========================================================================
select is(
    public.set_plugin_visibility(
        (select id from tp), 'unlisted', 'e0000000-0000-4000-8000-000000000001') ->> 'success',
    'true',
    'unlisted is accepted'
);

select is(
    public.set_plugin_visibility(
        (select id from tp), 'public', 'e0000000-0000-4000-8000-000000000001') ->> 'success',
    'true',
    'and public again'
);

-- Validated ahead of the column so a bad value is a sentence rather than a 23514 the caller
-- cannot read. Same reason submit_organisation_request checks before its CHECK.
select is(
    public.set_plugin_visibility(
        (select id from tp), 'secret', 'e0000000-0000-4000-8000-000000000001') ->> 'error',
    'Visibility must be public, org or unlisted',
    'an unknown value is refused readably, not as a constraint violation'
);

select is(
    public.set_plugin_visibility(
        (select id from tp), null, 'e0000000-0000-4000-8000-000000000001') ->> 'error',
    'Visibility must be public, org or unlisted',
    'and so is null'
);

select is(
    public.set_plugin_visibility(
        '99999999-9999-4999-8999-999999999999', 'public',
        'e0000000-0000-4000-8000-000000000001') ->> 'error',
    'Plugin not found',
    'an unknown plugin is refused before anything else'
);


-- ===========================================================================
-- A plugin with no organisation
-- ===========================================================================
-- user_is_org_admin(user, NULL) is false, so this would refuse anyway. Refusing BY NAME is the
-- point: "attribute it first" is a different instruction from "you are not an admin".
insert into public.plugins (author_id, author_name, plugin_id, display_name, type, api_version,
                            published, visibility, org_id)
values ('e0000000-0000-4000-8000-000000000004', 'Author', 'test.vis.orphan', 'Orphan',
        'panel', '1.0', true, 'public', null);

select is(
    public.set_plugin_visibility(
        (select id from public.plugins where plugin_id = 'test.vis.orphan'),
        'org', 'e0000000-0000-4000-8000-000000000001') ->> 'error',
    'This plugin belongs to no organisation, so no organisation can set its visibility',
    'an unattributed plugin says so rather than reporting a permission failure'
);


-- ===========================================================================
-- The reported change
-- ===========================================================================
select is(
    (public.set_plugin_visibility(
        (select id from tp), 'org', 'e0000000-0000-4000-8000-000000000001') ->> 'changed')::boolean,
    true,
    'a real change reports changed'
);

select is(
    (public.set_plugin_visibility(
        (select id from tp), 'org', 'e0000000-0000-4000-8000-000000000001') ->> 'changed')::boolean,
    false,
    'and setting the same value again reports it was not'
);


-- ===========================================================================
-- What restricting it costs the reader
-- ===========================================================================
-- Asserted because it is the consequence the page warns about, and a warning nothing checks is
-- just text. can_view_plugin_row short-circuits on public+published, so anything else is
-- invisible to an anonymous caller - which is how the Toolbox reads its catalogue.
select ok(
    not public.user_can_view_plugin_row(
        null, 'org', (select org from t), 'e0000000-0000-4000-8000-000000000004', true),
    'an org-visibility plugin is invisible to an anonymous reader'
);

select ok(
    public.user_can_view_plugin_row(
        null, 'public', (select org from t), 'e0000000-0000-4000-8000-000000000004', true),
    'and visible again once it is public'
);

select * from finish();
rollback;
