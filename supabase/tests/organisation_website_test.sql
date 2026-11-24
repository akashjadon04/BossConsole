-- pgTAP tests for the organisation website (migration 20260807000000).
-- Run with: supabase test db
--
-- The website is the one organisation field that is BOTH filled in by any
-- authenticated user (on a request, before any review) and rendered as a link on
-- a page other members read. That combination is what makes the scheme check a
-- security property rather than tidiness: a javascript: or data: URL stored here
-- would be self-service stored XSS.
--
-- It is checked in two places on purpose - the RPC, so a typo comes back as a
-- sentence, and the column CHECK, so nothing that bypasses the RPC can store one.
-- Both are asserted below, because a test that only exercised the RPC would pass
-- with the constraint dropped.

begin;
select plan(23);

insert into auth.users (id, email, email_confirmed_at) values
    ('90000000-0000-0000-0000-000000000001', 'websiteasker@pgtap.test', now()),
    ('90000000-0000-0000-0000-000000000002', 'websiteadmin@pgtap.test', now());

insert into public.user_roles (user_id, role_id)
select '90000000-0000-0000-0000-000000000002', r.id
  from public.roles r where r.name = 'admin'
on conflict do nothing;

select set_config('request.jwt.claims', '{"role":"service_role"}', true);


-- ===========================================================================
-- The RPC refuses anything that is not http or https
-- ===========================================================================
select is(
    (select public.submit_organisation_request(
        'Evil', 'evilsite', null, null, null,
        'javascript:alert(1)', '90000000-0000-0000-0000-000000000001') ->> 'error'),
    'Website must be a full http:// or https:// address',
    'a javascript: URL is refused'
);

select is(
    (select public.submit_organisation_request(
        'Data', 'datasite', null, null, null,
        'data:text/html,<script>alert(1)</script>', '90000000-0000-0000-0000-000000000001') ->> 'error'),
    'Website must be a full http:// or https:// address',
    'a data: URL is refused'
);

select is(
    (select public.submit_organisation_request(
        'Bare', 'baresite', null, null, null,
        'acme.com', '90000000-0000-0000-0000-000000000001') ->> 'error'),
    'Website must be a full http:// or https:// address',
    'a bare domain with no scheme is refused, so it cannot render as a relative link'
);

select is(
    (select public.submit_organisation_request(
        'Long', 'longsite', null, null, null,
        'https://' || repeat('a', 500) || '.com', '90000000-0000-0000-0000-000000000001') ->> 'error'),
    'Website must be 500 characters or fewer',
    'an over-long website is refused'
);


-- ===========================================================================
-- A valid one is stored, and survives approval onto the organisation
-- ===========================================================================
select ok(
    (public.submit_organisation_request(
        'Acme', 'acmesite', 'Acme Corp', 'acmesite.example', 'Because.',
        'https://acmesite.example', '90000000-0000-0000-0000-000000000001') ->> 'success')::boolean,
    'a valid https website is accepted'
);

select is(
    (select website from public.organisation_requests where slug = 'acmesite'),
    'https://acmesite.example',
    'the website is stored on the request'
);

select ok(
    (public.approve_organisation_request(
        (select id from public.organisation_requests where slug = 'acmesite'),
        null, '90000000-0000-0000-0000-000000000002') ->> 'success')::boolean,
    'the request can be approved'
);

-- The whole point of asking at request time: it has to reach the organisation.
select is(
    (select website from public.organisations where slug = 'acmesite'),
    'https://acmesite.example',
    'approval carries the website onto the organisation'
);

-- And it has to reach the page that renders it.
select is(
    (public.get_organisation_detail(
        (select id from public.organisations where slug = 'acmesite'),
        '90000000-0000-0000-0000-000000000002') -> 'data' ->> 'website'),
    'https://acmesite.example',
    'get_organisation_detail projects the website, so a page can render it'
);


-- ===========================================================================
-- An organisation without one is not broken by it
-- ===========================================================================
select ok(
    (public.submit_organisation_request(
        'Plain', 'plainsite', null, null, null,
        'https://plainsite.example', '90000000-0000-0000-0000-000000000001') ->> 'success')::boolean,
    'a second request is accepted'
);


-- ===========================================================================
-- The column CHECK, independent of the RPC
-- ===========================================================================
-- Asserted separately because every case above goes through the RPC, and all of
-- them would still pass with the constraint dropped.
select throws_ok(
    $$update public.organisations set website = 'javascript:alert(1)' where slug = 'acmesite'$$,
    '23514',
    null,
    'the column CHECK refuses a javascript: URL written directly'
);

select throws_ok(
    $$update public.organisations set website = '' where slug = 'acmesite'$$,
    '23514',
    null,
    'an empty string is refused, so "unset" has exactly one representation'
);

-- ===========================================================================
-- The ADMIN path, which is the one that is actually used
-- ===========================================================================
-- Everything above goes through submit_organisation_request, which a person uses
-- once. update_organisation_settings is what an administrator uses, and it had no
-- validation at all: the column CHECK raised 23514, which aborted the WHOLE
-- UPDATE, so a mistyped website silently discarded every other field in the same
-- submission and the page could only say "the change was refused".
select is(
    (public.update_organisation_settings(
        (select id from public.organisations where slug = 'acmesite'),
        'Renamed', null, null, null, null, null, false,
        'acmesite.example', false, '90000000-0000-0000-0000-000000000002') ->> 'error'),
    'Website must be a full http:// or https:// address',
    'the admin path returns a readable error rather than raising a constraint violation'
);

select is(
    (select name from public.organisations where slug = 'acmesite'),
    'Acme',
    'and it fails closed - the name in the same submission was not applied'
);

-- The empty-means-empty rule, which is deliberately not COALESCE.
select ok(
    (public.update_organisation_settings(
        (select id from public.organisations where slug = 'acmesite'),
        null, null, null, null, null, null, false, '', false,
        '90000000-0000-0000-0000-000000000002') ->> 'success')::boolean,
    'an empty website is accepted by the admin path'
);
select is(
    (select website from public.organisations where slug = 'acmesite'),
    null,
    'an empty string CLEARS the website'
);

-- SET A REAL VALUE FIRST. The previous version passed NULL while the column was
-- already NULL, so "leaves it alone" could not be told from "cleared it" - the
-- exact confusion the CASE WHEN exists to prevent, and it would have stayed green
-- under website = COALESCE(p_website, website).
select ok(
    (public.update_organisation_settings(
        (select id from public.organisations where slug = 'acmesite'),
        null, null, null, null, null, null, false, 'https://restored.example', false,
        '90000000-0000-0000-0000-000000000002') ->> 'success')::boolean,
    'a website can be set again after being cleared'
);
select is(
    (select website from public.organisations where slug = 'acmesite'),
    'https://restored.example',
    'and it is stored'
);

select ok(
    (public.update_organisation_settings(
        (select id from public.organisations where slug = 'acmesite'),
        'Acme', null, null, null, null, null, false, null, false,
        '90000000-0000-0000-0000-000000000002') ->> 'success')::boolean,
    'a NULL website is accepted'
);
select is(
    (select website from public.organisations where slug = 'acmesite'),
    'https://restored.example',
    'and a NULL LEAVES IT ALONE - it did not clear a website that was really set'
);

-- Case-insensitivity, untested on either side until now.
select ok(
    (public.update_organisation_settings(
        (select id from public.organisations where slug = 'acmesite'),
        null, null, null, null, null, null, false, 'HTTPS://Acme.Example', false,
        '90000000-0000-0000-0000-000000000002') ->> 'success')::boolean,
    'an upper-case scheme is accepted, matching the column CHECK regex'
);

-- The approval path refusing a bad value, which nothing covered.
select isnt(
    (select public.submit_organisation_request(
        'Bad Approve', 'badapprove', null, null, null,
        'ftp://acme.example', '90000000-0000-0000-0000-000000000001') ->> 'error'),
    null,
    'a non-http scheme never reaches approval, because submit refuses it'
);


-- ===========================================================================
-- An approver can see what they are approving
-- ===========================================================================
-- The threat model is that any authenticated user supplies this and it becomes a
-- link on a page other members read. Review is the control between those two
-- facts, and the queue was not projecting the field at all.
select is(
    (select r ->> 'website'
       from jsonb_array_elements(
                public.list_organisation_requests(
                    p_actor_id => '90000000-0000-0000-0000-000000000001') -> 'data') r
      where r ->> 'slug' = 'plainsite'),
    'https://plainsite.example',
    'the request queue shows the website an approver is about to publish'
);

select * from finish();
rollback;
