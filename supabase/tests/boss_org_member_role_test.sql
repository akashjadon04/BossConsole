-- pgTAP tests for the boss organisation's member role (migration 20260806000000).
-- Run with: supabase test db
--
-- The migration has four parts and three of them are load-bearing in a way that
-- reading the SQL does not reveal:
--
--   * The UPDATE that sets the flag runs against an organisation that does not
--     exist yet on a fresh deployment, so it reports success against zero rows.
--     ensure_boss_organisation() is what actually decides the value there, and it
--     hardcoded false - so the UPDATE alone was silently undone by the next reset.
--   * handle_new_user inserts the membership row DIRECTLY and never called
--     assign_org_member_role_internal, so the flag alone changed nothing for a new
--     signup. Every other entry path went through the helper; this one did not.
--   * The backfill must not reach a pending or invited row, or it hands out
--     organisation standing that the approval step exists to gate.
--
-- The whole suite otherwise runs against a database with NO users, where the boss
-- organisation does not exist and this entire code path is skipped - so a green
-- run proves nothing about it unless the organisation is created first, which is
-- the first thing below.

begin;
select plan(14);

-- ---------------------------------------------------------------------------
-- Fixtures: the boss organisation has to exist, or every assertion here is vacuous
-- ---------------------------------------------------------------------------
insert into auth.users (id, email, email_confirmed_at) values
    ('70000000-0000-0000-0000-000000000001', 'bossfounder@pgtap.test', now());

select set_config('request.jwt.claims', '{"role":"service_role"}', true);

-- The documented recovery path, and what a fresh deployment runs.
select ok(
    (public.ensure_boss_organisation() ->> 'success')::boolean,
    'the boss organisation can be created'
);

-- ===========================================================================
-- The flag survives creation
-- ===========================================================================
select is(
    (select auto_assign_member_role from public.organisations where slug = 'boss'),
    true,
    'ensure_boss_organisation creates the boss organisation with auto-assign ON'
);

-- ===========================================================================
-- A new signup receives the organisation role
-- ===========================================================================
insert into auth.users (id, email, email_confirmed_at) values
    ('70000000-0000-0000-0000-000000000002', 'bossjoiner@pgtap.test', now());

select is(
    (select m.status
       from public.organisation_members m
       join public.organisations o on o.id = m.org_id
      where o.slug = 'boss' and m.user_id = '70000000-0000-0000-0000-000000000002'),
    'active',
    'a new signup becomes an active member of the boss organisation'
);

select ok(
    exists (
        select 1
          from public.user_roles ur
          join public.organisation_roles orl on orl.role_id = ur.role_id
          join public.organisations o on o.id = orl.org_id
         where o.slug = 'boss'
           and orl.kind = 'user'
           and ur.user_id = '70000000-0000-0000-0000-000000000002'
    ),
    'a new signup is assigned the boss organisation user role'
);

-- The global role is still assigned. The organisation role is in addition to it,
-- never instead of it - the baseline permissions ride on `user`.
select ok(
    exists (
        select 1 from public.user_roles ur
          join public.roles r on r.id = ur.role_id
         where r.name = 'user' and ur.user_id = '70000000-0000-0000-0000-000000000002'
    ),
    'the global user role is still assigned alongside it'
);

-- ===========================================================================
-- The assigned_at tie that primary_role rides on
-- ===========================================================================
-- handle_new_user assigns the global `user` role and then calls the helper, both
-- inside one transaction. now() is transaction_timestamp() and does not advance,
-- so before 20260806010000 both rows landed with an identical assigned_at.
--
-- get_user_roles_for_hook aggregates ORDER BY assigned_at and the token hook takes
-- element 1 as primary_role. A tie makes that claim plan-dependent, so a signup
-- could report its role as boss_org_user. Not an escalation - permissions come
-- from the full array - but nondeterministic and displayed.
select is(
    (select count(distinct ur.assigned_at)::int
       from public.user_roles ur
      where ur.user_id = '70000000-0000-0000-0000-000000000002'),
    2,
    'the two roles a signup receives have distinct assigned_at, so the sort has no tie'
);

select is(
    (select (public.get_user_roles_for_hook('70000000-0000-0000-0000-000000000002'))[1]),
    'user',
    'the global user role sorts first, so primary_role is deterministic'
);


-- ===========================================================================
-- The flag is what drives it, not a hardcoded organisation name
-- ===========================================================================
update public.organisations set auto_assign_member_role = false where slug = 'boss';

insert into auth.users (id, email, email_confirmed_at) values
    ('70000000-0000-0000-0000-000000000003', 'bossoptout@pgtap.test', now());

select ok(
    not exists (
        select 1
          from public.user_roles ur
          join public.organisation_roles orl on orl.role_id = ur.role_id
          join public.organisations o on o.id = orl.org_id
         where o.slug = 'boss' and orl.kind = 'user'
           and ur.user_id = '70000000-0000-0000-0000-000000000003'
    ),
    'turning the flag off stops the assignment immediately'
);

update public.organisations set auto_assign_member_role = true where slug = 'boss';

-- ===========================================================================
-- The backfill
-- ===========================================================================
-- ORDER MATTERS HERE. The signup trigger assigns the role on INSERT, while the
-- membership is still active, so creating the pending user after the delete would
-- hand them the role via the trigger and the assertion below would be measuring
-- the fixture instead of the backfill. Create first, demote, then strip.
insert into auth.users (id, email, email_confirmed_at) values
    ('70000000-0000-0000-0000-000000000004', 'bosspending@pgtap.test', now());
update public.organisation_members m
   set status = 'pending'
  from public.organisations o
 where o.id = m.org_id and o.slug = 'boss'
   and m.user_id = '70000000-0000-0000-0000-000000000004';

-- Strip the role from everyone, which is the state production was in before the
-- migration, then run the migration's statement verbatim.
delete from public.user_roles ur
 using public.organisation_roles orl
  join public.organisations o on o.id = orl.org_id
 where ur.role_id = orl.role_id and o.slug = 'boss' and orl.kind = 'user';

insert into public.user_roles (user_id, role_id, assigned_by, assigned_at)
select m.user_id, orl.role_id, null, now()
  from public.organisation_members m
  join public.organisations o
    on o.id = m.org_id and o.slug = 'boss' and o.is_system
  join public.organisation_roles orl
    on orl.org_id = o.id and orl.kind = 'user'
 where m.status = 'active'
on conflict (user_id, role_id) do nothing;

select is(
    (select count(*)::int
       from public.user_roles ur
       join public.organisation_roles orl on orl.role_id = ur.role_id
       join public.organisations o on o.id = orl.org_id
      where o.slug = 'boss' and orl.kind = 'user'),
    (select count(*)::int
       from public.organisation_members m
       join public.organisations o on o.id = m.org_id
      where o.slug = 'boss' and m.status = 'active'),
    'the backfill assigns the role to exactly the active members'
);

select ok(
    not exists (
        select 1
          from public.user_roles ur
          join public.organisation_roles orl on orl.role_id = ur.role_id
          join public.organisations o on o.id = orl.org_id
         where o.slug = 'boss' and orl.kind = 'user'
           and ur.user_id = '70000000-0000-0000-0000-000000000004'
    ),
    'the backfill does not reach a member who is still pending approval'
);

-- Re-running must be inert: the migration can be applied to an environment where
-- the seed or a previous run already did the work.
insert into public.user_roles (user_id, role_id, assigned_by, assigned_at)
select m.user_id, orl.role_id, null, now()
  from public.organisation_members m
  join public.organisations o
    on o.id = m.org_id and o.slug = 'boss' and o.is_system
  join public.organisation_roles orl
    on orl.org_id = o.id and orl.kind = 'user'
 where m.status = 'active'
on conflict (user_id, role_id) do nothing;

select is(
    (select count(*)::int
       from public.user_roles ur
       join public.organisation_roles orl on orl.role_id = ur.role_id
       join public.organisations o on o.id = orl.org_id
      where o.slug = 'boss' and orl.kind = 'user'),
    (select count(*)::int
       from public.organisation_members m
       join public.organisations o on o.id = m.org_id
      where o.slug = 'boss' and m.status = 'active'),
    're-running the backfill changes nothing'
);

-- ===========================================================================
-- The repair clears a tie that already exists
-- ===========================================================================
-- The assertions above prove a tie cannot be CREATED any more. They say nothing
-- about the rows written between 20260806000000 and 20260806010000, whose tie is
-- permanent and leaves primary_role plan-dependent for the life of the row.
--
-- Recreate that state by hand - set the organisation role's assigned_at back onto
-- its peer - then run 20260806020000's statement verbatim.
update public.user_roles ur
   set assigned_at = peer.assigned_at
  from public.user_roles peer,
       public.organisation_roles orl,
       public.roles r
 where ur.user_id = '70000000-0000-0000-0000-000000000002'
   and orl.role_id = ur.role_id and orl.kind = 'user'
   and peer.user_id = ur.user_id and peer.role_id = r.id and r.name = 'user';

select is(
    (select count(distinct assigned_at)::int from public.user_roles
      where user_id = '70000000-0000-0000-0000-000000000002'),
    1,
    'the historical tie is reproducible, so the repair below is not vacuous'
);

update public.user_roles ur
   set assigned_at = ur.assigned_at + interval '1 microsecond'
  from public.organisation_roles orl
 where orl.role_id = ur.role_id
   and orl.kind = 'user'
   and exists (
       select 1 from public.user_roles peer
        where peer.user_id = ur.user_id
          and peer.role_id <> ur.role_id
          and peer.assigned_at = ur.assigned_at
   );

select is(
    (select count(distinct assigned_at)::int from public.user_roles
      where user_id = '70000000-0000-0000-0000-000000000002'),
    2,
    'the repair clears an existing tie'
);

-- Direction, not just distinctness. Nudging the ORGANISATION row rather than its
-- peer is the whole point: `user` has to stay first, because the token hook takes
-- element 1 as primary_role. count(distinct) = 2 would also hold if the repair had
-- moved the wrong row.
select is(
    (public.get_user_roles_for_hook('70000000-0000-0000-0000-000000000002'))[1],
    'user',
    'and moves the ORGANISATION role later, so primary_role is still user'
);

select * from finish();
rollback;
