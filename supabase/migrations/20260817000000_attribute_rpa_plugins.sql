-- ============================================================================
-- BOSS Database Schema: attribute the RPA plugins to the risa organisation
--
-- File: 20260817000000_attribute_rpa_plugins.sql
--
-- The seventh, eighth and ninth. 20260812000000 named five plugins as RISA's and
-- 20260814000000 added Form Assist; these three are the RPA suite, identified
-- afterwards by the operator:
--
--   llmrpa       LLM RPA (Dynamic)       AI-driven automation, emits a plan
--   rparecorder  RPA Recorder (Dynamic)  records browser interactions
--   rpaengine    RPA Engine (Dynamic)    executes the recorded workflows
--
-- They are one product in three pieces - the recorder captures, LLM RPA plans, the
-- engine runs - so they move together or the store shows one product under two
-- owners.
--
-- A SEPARATE MIGRATION, NOT AN EDIT TO EITHER LIST. 20260812000000 and
-- 20260814000000 are applied on production, so editing them would drift the
-- recorded checksum and, worse, the edit would have no effect anywhere it had
-- already run while looking authoritative in the source.
--
-- NAMED, NOT DERIVED, for the reason 20260812000000 sets out at length:
-- `plugins.author_id` is whoever ran the publish, which is one person for most of
-- this store, so deriving ownership from it would sweep up plugins that are not
-- RISA's. An operator asserting ownership is the only correct source. Note that
-- `author_name` on all three already reads "Risa Labs" and that is NOT evidence -
-- it is a free-text field written at publish time, not an ownership record.
--
-- WHAT IT CHANGES FOR A READER: nothing. `visibility` is untouched and all three
-- are public and published, and user_can_view_plugin_row short-circuits on
-- public+published BEFORE it looks at org_id, so no reader loses access and
-- user_can_install_plugin is unaffected. What moves is the org arm of the UPDATE
-- policy: risa admins gain the ability to update these rows and boss admins lose
-- it. Global plugins-admins keep it either way through the permissive policy in
-- 20260131000000, so none of them becomes unmanageable.
--
-- ONLY ROWS STILL ON boss (or NULL) ARE MOVED, so a later deliberate attribution
-- outranks this file, and a second run finds nothing to do.
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
    -- plugin_id would otherwise look identical to a successful no-op, and no
    -- database test can catch that - a fixture written from the same typo passes.
    -- These three were checked against the live store by id before being written
    -- here.
    v_targets TEXT[] := ARRAY[
        'ai.rever.boss.plugin.dynamic.llmrpa',
        'ai.rever.boss.plugin.dynamic.rparecorder',
        'ai.rever.boss.plugin.dynamic.rpaengine'
    ];
    v_target  TEXT;
BEGIN
    SELECT o.id INTO v_risa_id FROM public.organisations o WHERE o.slug = 'risa';
    SELECT o.id INTO v_boss_id FROM public.organisations o WHERE o.slug = 'boss';

    -- Fail loudly rather than be marked applied having achieved nothing, which is
    -- the failure 20260806000000's backfill hit. Conditional on the plugins actually
    -- being present: a fresh deployment that has neither them nor the organisation
    -- is a legitimate no-op, while one holding the plugins but no risa organisation
    -- is a deployment this migration was not written for and should stop.
    IF v_risa_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM public.plugins p WHERE p.plugin_id = ANY(v_targets)) THEN
            RAISE EXCEPTION
                'No organisation with slug "risa", but the RPA plugins it should own are present. Refusing to guess.';
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

        IF v_row.org_id = v_risa_id THEN
            RAISE NOTICE 'ALREADY risa: %.', v_target;
            CONTINUE;
        END IF;

        IF v_row.org_id IS NOT NULL AND v_row.org_id <> v_boss_id THEN
            RAISE NOTICE 'SKIPPED %: already attributed to % - a deliberate choice outranks this file.',
                v_target, v_row.org_id;
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
