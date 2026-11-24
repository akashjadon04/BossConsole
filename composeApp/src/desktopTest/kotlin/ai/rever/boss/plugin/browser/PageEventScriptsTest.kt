package ai.rever.boss.plugin.browser

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The published contract, as code.
 *
 * This wrapper decides that the bridge reaches a script as a parameter rather than as a `window`
 * property, which is the single most consequential sentence in `setPageEventScript`'s KDoc - and
 * that KDoc has already drifted from it once, silently. A plugin written to the wrong shape gets
 * `undefined.emit` -> TypeError -> swallowed by this wrapper's own catch -> no event, ever, with
 * nothing in any log. So the shape is asserted here rather than trusted to prose.
 */
class PageEventScriptsTest {
    private val script = "document.addEventListener('submit', function () { __bossPageEvent.emit('x'); }, true);"

    private fun injection(slot: String = "bslot123") = PageEventScripts.injection(slot, script)

    @Test
    fun `the bridge arrives as a parameter named by the published constant`() {
        // If this ever becomes a window assignment again, every plugin written to the documented
        // parameter shape breaks at once, and quietly.
        assertTrue(
            injection().contains("(function ($PAGE_EVENT_BRIDGE) {"),
            "the script is not wrapped in a function taking the bridge:\n${injection()}",
        )
    }

    @Test
    fun `the caller's script is passed through verbatim`() {
        assertTrue(injection().contains(script), "the script body was altered")
    }

    @Test
    fun `the slot is read once and then deleted`() {
        val out = injection("bAbC123")
        val read = out.indexOf("var bridge = window.bAbC123")
        val deleted = out.indexOf("delete window.bAbC123")
        assertTrue(read >= 0, "the slot is never read")
        assertTrue(deleted >= 0, "the slot is never deleted")
        assertTrue(read < deleted, "the slot is deleted before it is read, so the bridge is lost")
        // And the delete must precede the caller's script, or a page script the wrapper invokes
        // could still reach the slot.
        assertTrue(deleted < out.indexOf(script), "the script runs while the slot is still on window")
    }

    @Test
    fun `nothing leaves the bridge on window under its published name`() {
        // The published name is a parameter. An assignment to window under it would resurrect every
        // problem the parameter shape exists to remove.
        assertFalse(
            injection().contains("window.$PAGE_EVENT_BRIDGE"),
            "the wrapper touches window.$PAGE_EVENT_BRIDGE",
        )
    }

    @Test
    fun `a throwing script is reported to the page console and not swallowed silently`() {
        // The failure that made the contract drift invisible: with an empty catch, a script that
        // dies at evaluation produces nothing anywhere.
        assertTrue(injection().contains("console.error"), "a throwing script reports nothing")
    }

    @Test
    fun `the slot name is an identifier, random, and carries no recognisable prefix`() {
        // A prefix would be a stable "this is BOSS" bit for any page that enumerates window keys in
        // the gap between the host writing the slot and this script deleting it - which is the one
        // thing the random name cannot prevent.
        val a = PageEventScripts.newSlot { "0f8b7c6d5e4f3a2b1c0d9e8f7a6b5c4d" }
        val b = PageEventScripts.newSlot { "ffffffffffffffffffffffffffffffff" }
        assertTrue(a.first().isLetter(), "slot does not start with a letter: $a")
        assertTrue(a.all { it.isLetterOrDigit() }, "slot is not a bare identifier: $a")
        assertFalse(a == b, "the slot name does not vary with the random source")
        assertFalse(a.contains("boss", ignoreCase = true), "the slot name names BOSS: $a")
        assertFalse(a.contains("pageEvent", ignoreCase = true), "the slot name names the feature: $a")
    }
}
