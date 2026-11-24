-- pgTAP tests for attributing the RISA plugins (20260812000000).
-- Run with: supabase test db
--
-- The migration is a DO block over a fixed list, so unlike 20260811000000 there is no function for
-- a test to call - these run the same statement shape against fixtures and assert the rules the
-- block encodes. The assertions worth having are the ones about what it must NOT touch: the whole
-- risk of a named list is that it moves a row somebody had deliberately placed elsewhere, or that
-- a typo makes it silently move nothing.
--
-- The most important assertion is the last one: visibility is untouched, so nobody loses access.

begin;
select plan(14);

insert into auth.users (id, email, email_confirmed_at) values
    ('d0000000-0000-4000-8000-000000000001', 'author@risa.test', now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

select ok(
    (public.ensure_boss_organisation() ->> 'success')::boolean,
    'the boss organisation exists, so rows can start where the backfill put them'
);

select ok(
    (public.create_organisation_internal(
        p_slug => 'risa', p_name => 'Risa Labs Inc', p_description => null,
        p_owner_id => 'd0000000-0000-4000-8000-000000000001',
        p_domain => null, p_visibility => 'private',
        p_join_policy => 'invite_only') ->> 'success')::boolean,
    'the risa organisation exists'
);

create temporary table t_ids as
select (select id from public.organisations where slug = 'risa') as risa,
       (select id from public.organisations where slug = 'boss') as boss;

-- A third organisation, to stand for "somebody already placed this deliberately".
select ok(
    (public.create_organisation_internal(
        p_slug => 'thirdco', p_name => 'Third Co', p_description => null,
        p_owner_id => 'd0000000-0000-4000-8000-000000000001',
        p_domain => null, p_visibility => 'private',
        p_join_policy => 'invite_only') ->> 'success')::boolean,
    'a third organisation exists'
);

-- Fixtures. plugins_default_org fills org_id with boss on insert, which is exactly the state
-- production is in, so most of these are inserted without naming one.
insert into public.plugins (author_id, author_name, plugin_id, display_name, type, api_version, published, visibility)
values
    ('d0000000-0000-4000-8000-000000000001', 'Risa Labs',
     'ai.rever.boss.plugin.dynamic.medical-necessity', 'Medical Necessity', 'panel', '1.0', true, 'public'),
    ('d0000000-0000-4000-8000-000000000001', 'Risa Labs',
     'ai.rever.boss.plugin.dynamic.codexglm', 'Codex GLM', 'panel', '1.0', true, 'public'),
    -- Not on the list. Must not move.
    ('d0000000-0000-4000-8000-000000000001', 'Risa Labs',
     'ai.rever.boss.plugin.dynamic.terminaltab', 'Terminal Tab', 'tab', '1.0', true, 'public');

-- On the list, but already placed somewhere deliberate.
insert into public.plugins (author_id, author_name, plugin_id, display_name, type, api_version, published, visibility, org_id)
values ('d0000000-0000-4000-8000-000000000001', 'Risa Labs',
        'ai.rever.boss.plugin.dynamic.finances', 'Finances', 'panel', '1.0', true, 'public',
        (select thirdco.id from public.organisations thirdco where thirdco.slug = 'thirdco'));

select is(
    (select count(*)::int from public.plugins p
      where p.org_id = (select boss from t_ids)),
    3,
    'the fixtures start on boss, which is what the trigger does and what production looks like'
);


-- ===========================================================================
-- The migration's statement, verbatim in shape
-- ===========================================================================
DO $$
DECLARE
    v_risa_id UUID;
    v_boss_id UUID;
    v_row     RECORD;
    v_targets TEXT[] := ARRAY[
        'ai.rever.boss.plugin.dynamic.medical-necessity',
        'ai.rever.boss.plugin.dynamic.mednec',
        'ai.rever.boss.plugin.dynamic.mednec-copilot',
        'ai.rever.boss.plugin.dynamic.codexglm',
        'ai.rever.boss.plugin.dynamic.finances'
    ];
    v_target  TEXT;
BEGIN
    SELECT o.id INTO v_risa_id FROM public.organisations o WHERE o.slug = 'risa';
    SELECT o.id INTO v_boss_id FROM public.organisations o WHERE o.slug = 'boss';

    FOREACH v_target IN ARRAY v_targets LOOP
        SELECT p.id, p.org_id INTO v_row FROM public.plugins p WHERE p.plugin_id = v_target;
        IF NOT FOUND THEN CONTINUE; END IF;
        IF v_row.org_id IS NOT NULL AND v_row.org_id <> v_boss_id AND v_row.org_id <> v_risa_id THEN
            CONTINUE;
        END IF;
        IF v_row.org_id = v_risa_id THEN CONTINUE; END IF;
        UPDATE public.plugins SET org_id = v_risa_id WHERE id = v_row.id;
    END LOOP;
END;
$$;


-- ===========================================================================
-- What moved
-- ===========================================================================
select is(
    (select org_id from public.plugins where plugin_id = 'ai.rever.boss.plugin.dynamic.medical-necessity'),
    (select risa from t_ids),
    'a named plugin on boss moves to risa'
);

select is(
    (select org_id from public.plugins where plugin_id = 'ai.rever.boss.plugin.dynamic.codexglm'),
    (select risa from t_ids),
    'and so does the second one'
);

-- ===========================================================================
-- What did not
-- ===========================================================================
select is(
    (select org_id from public.plugins where plugin_id = 'ai.rever.boss.plugin.dynamic.terminaltab'),
    (select boss from t_ids),
    'a plugin NOT on the list is left on boss - the list is the whole scope'
);

select isnt(
    (select org_id from public.plugins where plugin_id = 'ai.rever.boss.plugin.dynamic.finances'),
    (select risa from t_ids),
    'a plugin already attributed elsewhere is NOT re-pointed, even though it is on the list'
);

select is(
    (select o.slug from public.organisations o
      join public.plugins p on p.org_id = o.id
     where p.plugin_id = 'ai.rever.boss.plugin.dynamic.finances'),
    'thirdco',
    'and keeps exactly the organisation somebody chose for it'
);

-- A name in the list with no matching row must be a silent skip, not an error that
-- aborts the whole migration and leaves the earlier rows moved but uncommitted.
select is(
    (select count(*)::int from public.plugins
      where plugin_id in ('ai.rever.boss.plugin.dynamic.mednec',
                          'ai.rever.boss.plugin.dynamic.mednec-copilot')),
    0,
    'two names in the list match no row here, and the block still completed'
);

-- ===========================================================================
-- Access is unchanged, which is the claim that matters most
-- ===========================================================================
select is(
    (select visibility from public.plugins where plugin_id = 'ai.rever.boss.plugin.dynamic.medical-necessity'),
    'public',
    'visibility is untouched'
);

-- can_view_plugin_row short-circuits on public+published BEFORE it looks at org_id, so moving the
-- organisation cannot remove anyone's read access. Asserted rather than assumed, because "we only
-- changed ownership" is exactly the kind of claim that turns out to have hidden a visibility change.
-- Argument order matters and is easy to get wrong: p_user_id comes FIRST, then visibility, org,
-- author, published. The first version of this test passed them in the order they appear in the
-- function BODY and died with 42883.
select ok(
    public.user_can_view_plugin_row(null, 'public', (select risa from t_ids), null, true),
    'an anonymous reader can still see it after the move'
);

-- p_plugin_id is the ROW's uuid, not the dotted plugin_id string. Passing the text silently
-- looks right and does not resolve.
select ok(
    public.user_can_install_plugin(
        'd0000000-0000-4000-8000-000000000001',
        (select id from public.plugins where plugin_id = 'ai.rever.boss.plugin.dynamic.medical-necessity')),
    'and it is still installable'
);

-- ===========================================================================
-- Re-running
-- ===========================================================================
DO $$
DECLARE
    v_risa_id UUID; v_boss_id UUID; v_row RECORD;
    v_targets TEXT[] := ARRAY['ai.rever.boss.plugin.dynamic.medical-necessity'];
    v_target TEXT;
BEGIN
    SELECT o.id INTO v_risa_id FROM public.organisations o WHERE o.slug = 'risa';
    SELECT o.id INTO v_boss_id FROM public.organisations o WHERE o.slug = 'boss';
    FOREACH v_target IN ARRAY v_targets LOOP
        SELECT p.id, p.org_id INTO v_row FROM public.plugins p WHERE p.plugin_id = v_target;
        IF v_row.org_id = v_risa_id THEN CONTINUE; END IF;
        UPDATE public.plugins SET org_id = v_risa_id WHERE id = v_row.id;
    END LOOP;
END;
$$;

select is(
    (select org_id from public.plugins where plugin_id = 'ai.rever.boss.plugin.dynamic.medical-necessity'),
    (select risa from t_ids),
    're-running leaves it on risa'
);

select * from finish();
rollback;
