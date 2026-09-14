-- pgTAP regression tests for fail-soft secret list decryption.
begin;
select plan(17);

select vault.create_secret(
    'cGd0YXAtdGVzdC1rZXktMzItYnl0ZXMtYWVzLW9r',
    'master_encryption_key',
    'pgTAP test key (transaction-local)');

insert into auth.users (id, email, email_confirmed_at) values
    ('d1900000-0000-4000-8000-000000000001', 'resilience@pgtap.test', now()),
    ('d1900000-0000-4000-8000-000000000002', 'sharer@pgtap.test', now());

select set_config(
    'request.jwt.claims',
    '{"sub":"d1900000-0000-4000-8000-000000000001","role":"authenticated"}',
    true);
set local role authenticated;

create temporary table secret_fixture (label text primary key, id uuid not null);
insert into secret_fixture
select 'good', (public.create_secret('resilience.example', 'good', 'good-password') ->> 'secret_id')::uuid;
insert into secret_fixture
select 'bad', (public.create_secret('resilience.example', 'bad', 'bad-password') ->> 'secret_id')::uuid;

reset role;
update public.secrets
set password_encrypted = 'not-valid-ciphertext'
where id = (select id from secret_fixture where label = 'bad');
insert into public.secret_metadata (secret_id, twofa_enabled, recovery_codes_encrypted)
values (
    (select id from secret_fixture where label = 'bad'),
    true,
    'not-valid-ciphertext'
);

-- A corrupt direct share exercises the separate sharing access path and proves
-- that decryption failure cannot erase its access metadata.
insert into public.secrets (id, user_id, website, username, password_encrypted)
values (
    'd1900000-0000-4000-8000-000000000003',
    'd1900000-0000-4000-8000-000000000002',
    'shared-resilience.example',
    'sharer',
    'not-valid-ciphertext'
);
insert into public.secret_shares (secret_id, shared_with_user_id, shared_by, access_level)
values (
    'd1900000-0000-4000-8000-000000000003',
    'd1900000-0000-4000-8000-000000000001',
    'd1900000-0000-4000-8000-000000000002',
    'read'
);

select is(
    public.try_decrypt_text(public.encrypt_text('round-trip')),
    'round-trip',
    'the internal helper returns plaintext for valid ciphertext'
);
select is(
    public.try_decrypt_text('not-valid-ciphertext'),
    null::text,
    'the internal helper returns null instead of raising for corrupt ciphertext'
);

set local role authenticated;

select is(
    (select password from public.get_user_secrets() where id = (select id from secret_fixture where label = 'good')),
    'good-password',
    'get_user_secrets keeps returning decryptable secrets'
);
select is(
    (select password from public.get_user_secrets() where id = (select id from secret_fixture where label = 'bad')),
    ''::text,
    'get_user_secrets preserves its non-null password contract for corrupt data'
);
select is(
    (select metadata->'recovery_codes' from public.get_user_secrets() where id = (select id from secret_fixture where label = 'bad')),
    '[]'::jsonb,
    'get_user_secrets returns empty recovery codes when they cannot decrypt'
);
select is(
    (select password from public.search_user_secrets('resilience.example') where id = (select id from secret_fixture where label = 'good')),
    'good-password',
    'search_user_secrets keeps returning decryptable secrets'
);
select is(
    (select password from public.search_user_secrets('resilience.example') where id = (select id from secret_fixture where label = 'bad')),
    ''::text,
    'search_user_secrets preserves its non-null password contract for corrupt data'
);
select is(
    (select metadata->'recovery_codes' from public.search_user_secrets('resilience.example') where id = (select id from secret_fixture where label = 'bad')),
    '[]'::jsonb,
    'search_user_secrets returns empty recovery codes when they cannot decrypt'
);
select is(
    (select password from public.get_user_secrets_with_shared() where id = (select id from secret_fixture where label = 'good')),
    'good-password',
    'get_user_secrets_with_shared keeps returning decryptable secrets'
);
select is(
    (select password from public.get_user_secrets_with_shared() where id = (select id from secret_fixture where label = 'bad')),
    ''::text,
    'get_user_secrets_with_shared preserves its non-null password contract for corrupt data'
);
select is(
    (select metadata->'recovery_codes' from public.get_user_secrets_with_shared() where id = (select id from secret_fixture where label = 'bad')),
    '[]'::jsonb,
    'get_user_secrets_with_shared returns empty recovery codes when they cannot decrypt'
);
select throws_ok(
    $$ select public.try_decrypt_text('not-valid-ciphertext') $$,
    '42501',
    null,
    'authenticated users cannot use the helper as a decryption oracle'
);
select is(
    (select count(*)::int from public.get_user_secrets() where website = 'resilience.example'),
    2,
    'a corrupt secret still appears as one row rather than aborting the list'
);
select is(
    (select password from public.get_user_secrets_with_shared() where website = 'shared-resilience.example'),
    ''::text,
    'a corrupt direct share remains visible with a blank password'
);
select is(
    (select access_level from public.get_user_secrets_with_shared() where website = 'shared-resilience.example'),
    'read',
    'a corrupt direct share retains its access level'
);
select is(
    (select shared_by_email from public.get_user_secrets_with_shared() where website = 'shared-resilience.example'),
    'sharer@pgtap.test',
    'a corrupt direct share retains sharing attribution'
);
select ok(
    not has_function_privilege('anon', 'public.try_decrypt_text(text)', 'EXECUTE')
        and not has_function_privilege('service_role', 'public.try_decrypt_text(text)', 'EXECUTE'),
    'the fail-soft helper remains inaccessible to anonymous and service roles'
);

reset role;
select * from finish();
rollback;
