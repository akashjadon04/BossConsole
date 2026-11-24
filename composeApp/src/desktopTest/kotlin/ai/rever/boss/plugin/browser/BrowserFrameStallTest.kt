package ai.rever.boss.plugin.browser

import com.teamdev.jxbrowser.engine.RenderingMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards the pure decisions in [BrowserFrameStall] - the ones that decide whether a user's browser
 * view gets yanked out of composition and put back.
 *
 * Both fail in the same direction when got wrong: re-attaching too often, which is a visible
 * flicker on pages that were never broken.
 *
 * **What this does not cover**, so green here is not mistaken for a verified mechanism: the
 * supersede-per-redirect logic, the visibility gate, the cap and cooldown, and the re-attach
 * itself all need a live JxBrowser view and are covered only by the manual run recorded in the PR
 * (same click path: 0/5 painted before, 5/5 after, watchdog firing only on the AI Mode commits).
 */
class BrowserFrameStallTest {
    @Test
    fun `only http and https pages are watched`() {
        val mode = RenderingMode.HARDWARE_ACCELERATED
        assertTrue(BrowserFrameStall.shouldWatch("https://www.google.com/search?q=x&udm=50&aep=1", mode))
        assertTrue(BrowserFrameStall.shouldWatch("http://example.com", mode))
        assertTrue(BrowserFrameStall.shouldWatch("  https://example.com  ", mode), "a padded URL is still a web page")
        assertTrue(BrowserFrameStall.shouldWatch("HTTPS://EXAMPLE.COM", mode), "scheme is case-insensitive")
    }

    @Test
    fun `internal and empty pages are never watched`() {
        val mode = RenderingMode.HARDWARE_ACCELERATED
        // about:blank and the dashboard's empty URL are what a tab shows when it is *meant* to be
        // empty. Probing them would arm a beacon on a page with no rAF work and then re-attach the
        // view for it, so the blank the user asked for would flicker.
        for (url in listOf(null, "", "   ", "about:blank", "chrome://gpu", "file:///tmp/x.html", "boss://terminal")) {
            assertFalse(BrowserFrameStall.shouldWatch(url, mode), "should not watch $url")
        }
    }

    @Test
    fun `OFF_SCREEN is never watched`() {
        // The stall is an attachment fault in the native-view path. OFF_SCREEN exports frames as a
        // bitmap and has never shown it, so watching there would be pure risk with no upside.
        assertFalse(
            BrowserFrameStall.shouldWatch("https://www.google.com/search?q=x&udm=50&aep=1", RenderingMode.OFF_SCREEN),
        )
    }

    @Test
    fun `only an explicit unpainted reading is a stall`() {
        assertTrue(BrowserFrameStall.isStalled(BrowserFrameStall.BEACON_UNPAINTED))
        assertFalse(BrowserFrameStall.isStalled(BrowserFrameStall.BEACON_PAINTED))
    }

    @Test
    fun `a failed or timed-out beacon read is not a stall`() {
        // The distinction this test exists for: null means the JS round-trip did not happen or did
        // not answer in time (a navigation raced the probe, the frame went away, the renderer is
        // busy), NOT that the page failed to draw. Treating null as a stall re-attaches the view
        // on ordinary pages, and does it hardest against exactly the slow renderers least able to
        // afford it.
        assertFalse(BrowserFrameStall.isStalled(null))
    }

    @Test
    fun `an unknown post-repair reading is neither a recovery nor a failure`() {
        // The highest-severity finding from review, and the reason repairOutcome exists rather
        // than reusing isStalled: `!isStalled(null)` is true, so an unanswered probe read as a
        // successful repair. That cleared the ineffective run in exactly the case the cap exists
        // for - a renderer too wedged to answer at all - so the cap could never trip, the tab
        // flickered once per cooldown forever, and the log claimed it was painting.
        assertNull(BrowserFrameStall.repairOutcome(null))
        assertEquals(false, BrowserFrameStall.repairOutcome(BrowserFrameStall.BEACON_UNPAINTED))
        assertEquals(true, BrowserFrameStall.repairOutcome(BrowserFrameStall.BEACON_PAINTED))
    }

    @Test
    fun `an unrecognised post-repair reading counts as recovered, matching isStalled`() {
        // Deliberately the same cautious polarity as isStalled: only the exact unpainted marker
        // is evidence of failure, so a page that overwrote the global cannot force the cap to
        // trip and retire its own repair.
        assertEquals(true, BrowserFrameStall.repairOutcome("[object Object]"))
    }

    @Test
    fun `an unrecognised reading is not a stall`() {
        // The beacon is a window global, so a page can overwrite it with anything. Anything that
        // is not the exact unpainted marker is treated as "do not touch this".
        for (reading in listOf("", "true", "0.0", "unarmed", "[object Object]")) {
            assertFalse(BrowserFrameStall.isStalled(reading), "should not treat $reading as a stall")
        }
    }

    @Test
    fun `the beacon script arms once per document and reports what isStalled compares against`() {
        // The same snippet arms and polls, so a rename on one side cannot silently stop the other
        // from ever matching - which would re-attach the view on every single navigation.
        assertTrue(BrowserFrameStall.BEACON_SCRIPT.contains("__bossFrameBeacon"))
        assertTrue(BrowserFrameStall.BEACON_SCRIPT.contains("requestAnimationFrame"))
        assertTrue(
            BrowserFrameStall.BEACON_SCRIPT.contains("'${BrowserFrameStall.BEACON_PAINTED}'"),
            "the script must assign the exact value isStalled compares against",
        )
        assertTrue(
            BrowserFrameStall.BEACON_SCRIPT.contains("'${BrowserFrameStall.BEACON_UNPAINTED}'"),
            "the script must seed the exact value isStalled treats as stalled",
        )
        // The typeof guard is what makes arming once-per-document rather than once-per-call, and
        // that is what lets a second reading confirm the first instead of restarting the clock.
        assertTrue(
            BrowserFrameStall.BEACON_SCRIPT.contains("typeof window.__bossFrameBeacon === 'undefined'"),
            "re-arming on every call would make every reading the first one, which is always unpainted",
        )
    }

    @Test
    fun `the waits leave room for a page to paint`() {
        // An arm delay of zero would be harmless, but a zero read gap would judge the page in the
        // same tick it was armed in, when no page has painted yet.
        assertTrue(BrowserFrameStall.ARM_DELAY_MS >= 500)
        assertTrue(BrowserFrameStall.READ_GAP_MS >= 500)
        assertTrue(BrowserFrameStall.PROBE_TIMEOUT_MS > 0)
    }

    @Test
    fun `the repair is bounded, but only by how often it fails`() {
        // Unbounded, any condition that reliably reads unpainted becomes a flicker on every
        // navigation - worse than the blank being fixed. See EngineWedgeDetector for the sibling.
        assertTrue(BrowserFrameStall.MAX_INEFFECTIVE_REATTACHES in 1..10)
        // The rate limit is what bounds a tight navigation loop, and it applies whether or not the
        // repair is working - so it, not the give-up counter, has to be non-trivial.
        assertTrue(BrowserFrameStall.REATTACH_COOLDOWN_MS >= 1_000)
    }
}
