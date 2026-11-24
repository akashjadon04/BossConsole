package ai.rever.boss.layout

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The readout's on/off rule.
 *
 * Pulled out of the `by lazy` that reads the environment so it can be asserted at all. The case
 * worth having a test for is the blank env var: `BrowserAnalytics.telemetryEnabledFrom` records in
 * its KDoc that a blank value shadowing a set property was hit for real in this repo, and a flag
 * that reads two sources has no business rediscovering it.
 */
class ChromeBudgetReadoutFlagTest {
    @Test
    fun `off when neither source is set`() {
        assertFalse(chromeBudgetReadoutEnabled(env = null, property = null))
    }

    @Test
    fun `the env var turns it on`() {
        listOf("1", "true", "yes", "on", "TRUE", "On").forEach { value ->
            assertTrue(chromeBudgetReadoutEnabled(env = value, property = null), value)
        }
    }

    @Test
    fun `the system property turns it on`() {
        // The reason this source exists: `./gradlew run` makes an env var awkward to set.
        assertTrue(chromeBudgetReadoutEnabled(env = null, property = "1"))
    }

    @Test
    fun `a blank env var falls through to the property instead of shadowing it`() {
        listOf("", "   ").forEach { blank ->
            assertTrue(
                chromeBudgetReadoutEnabled(env = blank, property = "1"),
                "a blank env var swallowed the property, which is the bug this rule exists to avoid",
            )
        }
    }

    @Test
    fun `a set env var wins over the property`() {
        assertFalse(chromeBudgetReadoutEnabled(env = "0", property = "1"))
    }

    @Test
    fun `surrounding whitespace does not defeat it`() {
        assertTrue(chromeBudgetReadoutEnabled(env = " 1 ", property = null))
    }

    @Test
    fun `anything else is off`() {
        listOf("0", "false", "no", "off", "maybe", "2").forEach { value ->
            assertFalse(chromeBudgetReadoutEnabled(env = value, property = null), value)
        }
    }
}
