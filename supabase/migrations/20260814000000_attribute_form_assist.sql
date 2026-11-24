-- ============================================================================
-- BOSS Database Schema: attribute Form Assist to the risa organisation
--
-- File: 20260814000000_attribute_form_assist.sql
--
-- The sixth. 20260812000000 named five plugins as RISA's; Form Assist is another,
-- identified afterwards by the operator.
--
-- A SEPARATE MIGRATION, NOT AN EDIT TO THAT LIST. 20260812000000 is applied on
-- production, so editing it would change a file the migration history has already
-- recorded as run - the checksum drifts, and worse, the edit would have no effect
-- anywhere it had already been applied while looking authoritative in the source.
-- Naming a new one is the only version of this that does what it says on every
-- deployment.
--
-- NAMED, NOT DERIVED, for the reason 20260812000000 sets out at length:
-- `plugins.author_id` is whoever ran the publish, which is one person for most of
-- this store, so deriving ownership from it would sweep up plugins that are not
-- RISA's. An operator asserting ownership is the only correct source.
--
-- WHAT IT CHANGES FOR A READER: nothing. `visibility` is untouched and the row is
-- 'public', so can_view_plugin_row still returns true for everyone including
-- anonymous callers, and user_can_install_plugin is unaffected. What moves is the
-- org arm of the UPDATE policy: risa admins gain the ability to update this row and
-- boss admins lose it. Global plugins-admins keep it either way through the
-- permissive policy in 20260131000000, so it does not become unmanageable.
--
-- ONLY A ROW STILL ON boss (or NULL) IS MOVED, so a later deliberate attribution
-- outranks this file, and a second run finds nothing to do.
-- ============================================================================


DO $$
DECLARE
    v_risa_id UUID;
    v_boss_id UUID;
    v_row     RECORD;
    v_target  TEXT := 'ai.rever.boss.plugin.dynamic.form-assist';
BEGIN
    SELECT o.id INTO v_risa_id FROM public.organisations o WHERE o.slug = 'risa';
    SELECT o.id INTO v_boss_id FROM public.organisations o WHERE o.slug = 'boss';

    -- Fail loudly rather than be marked applied having achieved nothing, which is the
    -- failure 20260806000000's backfill hit. The exception is conditional on the
    -- plugin actually being present: a fresh deployment that has neither is a
    -- legitimate no-op, while a deployment holding the plugin but no risa
    -- organisation is one this migration was not written for.
    IF v_risa_id IS NULL THEN
        IF EXISTS (SELECT 1 FROM public.plugins p WHERE p.plugin_id = v_target) THEN
            RAISE EXCEPTION
                'No organisation with slug "risa", but % is present. Refusing to guess.', v_target;
        END IF;
        RAISE NOTICE 'No risa organisation and % is not present; nothing to do.', v_target;
        RETURN;
    END IF;

    SELECT p.id, p.plugin_id, p.org_id, p.visibility
      INTO v_row
      FROM public.plugins p
     WHERE p.plugin_id = v_target;

    IF NOT FOUND THEN
        -- A NOTICE, not an exception: the store is not the same on every deployment,
        -- and a local database that has never seen this plugin is not broken. Said out
        -- loud because a typo in the id above would otherwise look exactly like success.
        RAISE NOTICE 'SKIPPED %: no such plugin in the store.', v_target;
        RETURN;
    END IF;

    IF v_row.org_id = v_risa_id THEN
        RAISE NOTICE 'ALREADY risa: %.', v_target;
        RETURN;
    END IF;

    IF v_row.org_id IS NOT NULL AND v_row.org_id <> v_boss_id THEN
        RAISE NOTICE 'SKIPPED %: already attributed to % - a deliberate choice outranks this file.',
            v_target, v_row.org_id;
        RETURN;
    END IF;

    UPDATE public.plugins SET org_id = v_risa_id WHERE id = v_row.id;
    RAISE NOTICE 'MOVED % to risa (visibility stays %).', v_target, v_row.visibility;
END;
$$;
