-- ============================================================================
-- BOSS Database Schema: attribute the RISA-owned plugins to the risa organisation
--
-- File: 20260812000000_attribute_risa_plugins.sql
--
-- Every row in `plugins` sits on the boss organisation: 20260803000000 backfilled
-- them all there and nothing since has chosen otherwise. 20260811000000 fixed
-- that going FORWARD - a newly created plugin now takes its publisher's
-- organisation - but it deliberately does not re-derive ownership when a new
-- VERSION is published, because that would move a plugin between organisations
-- every time its publisher's memberships changed. So no amount of CI activity
-- moves an existing row, and the ones that are genuinely RISA's need naming.
--
-- NAMED EXPLICITLY, NOT DERIVED. This is the difference between this migration and
-- 20260811000000. The API keys had a derivable answer - their owner's single
-- non-system organisation - and deriving it was safer than a list that could go
-- stale. These five have no such answer: `plugins.author_id` is whoever happened
-- to run the publish, which is the same person for most of the store, so deriving
-- from it would sweep up plugins that are not RISA's. An operator asserting
-- ownership is the only correct source, so the list is the input.
--
-- The five, with why each is here:
--
--   medical-necessity  Medical Necessity
--   mednec             Medical Necessity - Rohith Team1
--   mednec-copilot     RISA Copilot - Medical Necessity
--   codexglm           Codex GLM
--   finances           Finances
--
-- WHAT THIS CHANGES FOR A READER, which is less than it looks: `visibility` is
-- untouched and all five are 'public', so `can_view_plugin_row` still returns true
-- for everyone including anonymous callers, and `user_can_install_plugin` is
-- unaffected. Nobody loses access to anything. What changes is the org arm of the
-- UPDATE policy - risa admins gain the ability to update these rows, and boss
-- admins lose it. Global plugins-admins keep it either way through the permissive
-- policy in 20260131000000, so no plugin becomes unmanageable.
--
-- ONLY ROWS STILL ON boss (or NULL) ARE MOVED. If one of these has since been
-- attributed deliberately somewhere else, that decision wins over this list.
-- Idempotent for the same reason: a second run finds nothing on boss.
-- ============================================================================


DO $$
DECLARE
    v_risa_id UUID;
    v_boss_id UUID;
    v_moved   INTEGER := 0;
    v_row     RECORD;
    -- The list lives here rather than in a WHERE ... IN so the loop can report on
    -- each one, including the ones it did NOT move. A plugin silently absent from
    -- this migration's output is the failure mode worth surfacing: a typo'd
    -- plugin_id would otherwise look identical to a successful no-op.
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

    -- Fail loudly. Silently doing nothing because the organisation is absent is how
    -- a migration gets marked applied while achieving nothing - the exact failure
    -- 20260806000000's UPDATE hit, where a backfill matched zero rows on a fresh
    -- deployment and nobody noticed until it was re-derived from scratch.
    --
    -- RAISE EXCEPTION rather than NOTICE because unlike that backfill this list is
    -- specific: there is no environment where these five plugins exist and the risa
    -- organisation does not, so its absence means the migration is being run
    -- somewhere it was not written for and should stop rather than guess.
    IF v_risa_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM public.plugins p WHERE p.plugin_id = ANY(v_targets)) THEN
            RAISE EXCEPTION
                'No organisation with slug "risa", but the plugins it should own are present. Refusing to guess.';
        END IF;
        RAISE NOTICE 'No risa organisation and none of the target plugins present; nothing to do.';
        RETURN;
    END IF;

    FOREACH v_target IN ARRAY v_targets LOOP
        SELECT p.id, p.plugin_id, p.org_id, p.visibility
          INTO v_row
          FROM public.plugins p
         WHERE p.plugin_id = v_target;

        IF NOT FOUND THEN
            RAISE NOTICE 'SKIPPED %: no such plugin in the store.', v_target;
            CONTINUE;
        END IF;

        IF v_row.org_id IS NOT NULL AND v_row.org_id <> v_boss_id AND v_row.org_id <> v_risa_id THEN
            RAISE NOTICE 'SKIPPED %: already attributed to % - a deliberate choice outranks this list.',
                v_target, v_row.org_id;
            CONTINUE;
        END IF;

        IF v_row.org_id = v_risa_id THEN
            RAISE NOTICE 'ALREADY risa: %.', v_target;
            CONTINUE;
        END IF;

        UPDATE public.plugins SET org_id = v_risa_id WHERE id = v_row.id;
        v_moved := v_moved + 1;
        RAISE NOTICE 'MOVED % to risa (visibility stays %).', v_target, v_row.visibility;
    END LOOP;

    RAISE NOTICE 'Attributed % of % named plugin(s) to the risa organisation.',
        v_moved, array_length(v_targets, 1);
END;
$$;
