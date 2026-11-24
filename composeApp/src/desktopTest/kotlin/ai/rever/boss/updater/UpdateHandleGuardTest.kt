package ai.rever.boss.updater

import ai.rever.boss.testsupport.repoRoot
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every method a window can call on its handle must go through `guard(...)`.
 *
 * A released handle belongs to a window that has closed, and the updater it delegates to is shared
 * by every other window. The guard is the only thing between "this window is gone" and "this window
 * can still mutate the state the surviving windows are looking at" - and forgetting it is invisible
 * at the call site, because an unguarded method works perfectly until the window closes.
 *
 * **Driven off reflection over [UpdateHandle], not off a scan for `override fun`.** The first
 * version of this test parsed the source for overrides and asked whether each body contained
 * `guard(`, and review found three holes in that, all of which this shape closes:
 *
 * - `override suspend fun checkForUpdates` did not match a regex requiring `override fun`, so the
 *   one awaitable member - the one whose released fallback is easiest to forget - was silently
 *   exempt, and so was every future suspend override.
 * - Each body was bounded by the *next* override, which left the LAST one running to end of file.
 *   That slice swallowed the declaration `private fun guard(operation: String)`, so the final
 *   override passed whatever it did. Appending a new method at the bottom of the class - the most
 *   natural place to add one - inherited that free pass, which is precisely the regression this
 *   exists to catch. The earlier "confirmed to fail before being kept" evidence was real but held
 *   only because `cancelDownload` happened not to be last.
 * - `release` passed by that same accident while being genuinely *exempt*, so the test could not
 *   tell "must not be guarded" from "forgot the guard".
 *
 * Asking for `guard("<name>")` rather than a bare `guard(` also catches a copy-pasted label, which
 * would otherwise log the wrong operation for the rest of that method's life.
 */
class UpdateHandleGuardTest {
    /**
     * Members that must NOT be guarded, with the reason - an explicit list, so an exemption is
     * stated rather than inherited from a parsing accident.
     *
     * `release` is the release mechanism itself: it has to work on an already-released handle, and
     * it is idempotent through its own `compareAndSet`. Guarding it would break double-release,
     * which [UpdateOwnershipTest] pins as a no-op.
     */
    private val exempt = setOf("release")

    /** Read-only members: no state to mutate, so nothing to guard. */
    private val properties = setOf("getWindowId", "isReleased", "getUpdateState", "getShowUpdateDialog")

    private fun ownershipSource(): String =
        File(
            repoRoot(),
            "composeApp/src/commonMain/kotlin/ai/rever/boss/updater/UpdateOwnership.kt",
        ).readText()

    /** The interface's callable surface, which is what a window actually holds. */
    private fun handleMethods(): List<String> =
        UpdateHandle::class.java.methods
            .map { it.name }
            .filterNot { it in properties }
            .filterNot { it.startsWith("get") && it.removePrefix("get").firstOrNull()?.isUpperCase() == true }
            // Kotlin emits a `name$default` bridge for every method with a default argument. It is
            // a compiler artifact that forwards to the real one, so it carries no guard of its own
            // and is not something a window can call by name.
            .filterNot { it.contains('$') }
            .distinct()

    @Test
    fun `every handle method is guarded, by name`() {
        val source = ownershipSource()
        val methods = handleMethods()

        // Vacuity: reflection returning nothing, or the property filter eating everything, would
        // otherwise make the assertion below trivially true.
        assertTrue(methods.size >= 8, "only ${methods.size} handle methods found - the reflection is wrong: $methods")

        val unguarded = methods.filterNot { it in exempt }.filterNot { source.contains("""guard("$it")""") }
        assertEquals(
            emptyList(),
            unguarded,
            "these let a released window mutate shared update state, or guard under the wrong label: $unguarded",
        )
    }

    @Test
    fun `the awaitable and the two new members are covered`() {
        // Named so that deleting one cannot quietly shrink the set above into a still-passing one.
        // checkForUpdates is the suspend member the previous regex missed entirely; the other two
        // are what the banner's Cancel buttons call.
        val methods = handleMethods()
        listOf("checkForUpdates", "cancelDownload", "discardDownloadInBackground").forEach {
            assertTrue(it in methods, "$it is not on UpdateHandle any more; the banner has nothing to call")
            assertTrue(ownershipSource().contains("""guard("$it")"""), "$it must be guarded")
        }
    }

    @Test
    fun `release is exempt on purpose, not by accident`() {
        // The bug in the previous version was that this passed as "guarded" because its body slice
        // ran into the private guard declaration. Assert the opposite explicitly.
        val source = ownershipSource()
        assertTrue(
            !source.contains("""guard("release")"""),
            "release must not be guarded - it has to work on an already-released handle",
        )
        assertTrue("release" in exempt, "release is exempt; keep the reason with the list")
    }
}
