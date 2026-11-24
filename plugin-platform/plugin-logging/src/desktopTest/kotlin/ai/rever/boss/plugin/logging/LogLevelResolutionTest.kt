package ai.rever.boss.plugin.logging

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins how the global log level is chosen: env > system property > dev-mode default.
 *
 * The blank rule is the reason this exists. `export BOSS_LOG_LEVEL=` yields an empty string, which
 * is non-null, so it used to win the chain and resolve through `LogLevel.fromString("")` to a level
 * nobody chose - shadowing both the system property and the dev-mode default with no way to tell
 * from the outside.
 *
 * Testable only because [BossLogger.resolveLevel] takes its sources as parameters: a JVM cannot set
 * its own environment variables, so a test driving `configureFromEnvironment` could never reach the
 * env branch. Same shape as `ConfigLoader.resolve`.
 */
class LogLevelResolutionTest {
    @Test
    fun `an explicit level wins, from either source`() {
        assertEquals(LogLevel.WARN, BossLogger.resolveLevel("WARN", null, devMode = false))
        assertEquals(LogLevel.ERROR, BossLogger.resolveLevel(null, "ERROR", devMode = false))
        // Env outranks the system property, matching every other config path in the app.
        assertEquals(LogLevel.WARN, BossLogger.resolveLevel("WARN", "ERROR", devMode = false))
    }

    @Test
    fun `a blank value falls through instead of shadowing the sources below it`() {
        for (blank in listOf("", "   ", "\t")) {
            assertEquals(
                LogLevel.ERROR,
                BossLogger.resolveLevel(blank, "ERROR", devMode = false),
                "blank env '$blank' must not shadow the system property",
            )
            assertEquals(
                LogLevel.DEBUG,
                BossLogger.resolveLevel(blank, blank, devMode = true),
                "blank env and property '$blank' must both fall through to the dev-mode default",
            )
        }
    }

    @Test
    fun `with nothing set the default follows dev mode`() {
        assertEquals(LogLevel.DEBUG, BossLogger.resolveLevel(null, null, devMode = true))
        assertEquals(LogLevel.INFO, BossLogger.resolveLevel(null, null, devMode = false))
    }
}
