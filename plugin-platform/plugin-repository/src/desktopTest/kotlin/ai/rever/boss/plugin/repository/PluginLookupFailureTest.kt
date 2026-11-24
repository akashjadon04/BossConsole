package ai.rever.boss.plugin.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * That [PluginRepositoryManager.getPlugin] tells "no repository has it" apart from "a repository could
 * not be asked".
 *
 * Both used to arrive as `success(null)`, because each repository's `Result` was flattened with
 * `getOrNull()`. The visible cost was a wrong diagnosis: one store row whose dependency entry failed
 * to decode made the first-run wizard report "Tool not found in repository" for a plugin that was
 * published and fine, and nothing short of the stack trace in the log said otherwise.
 *
 * Three properties here are the design rather than incidental behaviour, and each was a bug someone
 * could reintroduce while "simplifying":
 *  - a broken repository must not hide a plugin another repository can serve, so a failure is only
 *    reported when nothing was found;
 *  - a repository may throw instead of returning `Result.failure`, and both must arrive the same way;
 *  - the failure's message is bounded, because it reaches a wizard row while a kotlinx decode error
 *    carries the whole document it choked on.
 */
class PluginLookupFailureTest {
    @Test
    fun `no repository having it is absence, not failure`() =
        runTest {
            val manager = PluginRepositoryManager()
            manager.addRepository(FakeRepository(id = "local", isLocal = true, answer = Result.success(null)))

            val result = manager.getPlugin("ai.rever.boss.plugin.dynamic.flowtab")

            assertTrue(result.isSuccess, "an absent plugin is not an error")
            assertNull(result.getOrNull())
        }

    @Test
    fun `a repository that cannot answer is a failure, not absence`() =
        runTest {
            val manager = PluginRepositoryManager()
            manager.addRepository(
                FakeRepository(
                    id = "store",
                    isLocal = false,
                    answer = Result.failure(IllegalStateException("Field 'versionRange' is required")),
                ),
            )

            val result = manager.getPlugin("ai.rever.boss.plugin.dynamic.flowtab")

            val failure = result.exceptionOrNull()
            assertIs<PluginLookupException>(failure)
            assertEquals("ai.rever.boss.plugin.dynamic.flowtab", failure.pluginId)
            // The cause carries the detail; the message stays short enough for a UI row.
            assertTrue(
                "versionRange" in (failure.cause?.message ?: ""),
                "the real error must survive as the cause",
            )
            assertTrue(failure.message!!.length < 300, "message went to the wizard's list: ${failure.message}")
        }

    @Test
    fun `a broken repository cannot hide a plugin another one has`() =
        runTest {
            val manager = PluginRepositoryManager()
            manager.addRepository(
                FakeRepository(id = "local", isLocal = true, answer = Result.failure(IllegalStateException("boom"))),
            )
            manager.addRepository(
                FakeRepository(
                    id = "store",
                    isLocal = false,
                    answer = Result.success(pluginInfo("ai.rever.boss.plugin.dynamic.flowtab")),
                ),
            )

            val result = manager.getPlugin("ai.rever.boss.plugin.dynamic.flowtab")

            assertTrue(result.isSuccess, "a hit must win over an earlier repository's failure")
            assertEquals("store", result.getOrNull()?.source?.repositoryId)
        }

    @Test
    fun `a repository that throws is handled like one that returns a failure`() =
        runTest {
            // PluginRepository permits either. RemotePluginRepository returns, but a third-party
            // repository need not, and an unwrapped throwable would escape as itself - unbounded
            // message and all.
            val manager = PluginRepositoryManager()
            manager.addRepository(ThrowingRepository(id = "store"))

            val result = manager.getPlugin("ai.rever.boss.plugin.dynamic.flowtab")

            assertIs<PluginLookupException>(result.exceptionOrNull())
        }

    @Test
    fun `two repositories sharing one throwable do not turn into a self-suppression error`() =
        runTest {
            // addSuppressed(itself) throws IllegalArgumentException("Self-suppression not permitted"),
            // which would then be the message the user sees instead of the real cause.
            val shared = IllegalStateException("Field 'versionRange' is required")
            val manager = PluginRepositoryManager()
            manager.addRepository(FakeRepository(id = "local", isLocal = true, answer = Result.failure(shared)))
            manager.addRepository(FakeRepository(id = "store", isLocal = false, answer = Result.failure(shared)))

            val failure = manager.getPlugin("ai.rever.boss.plugin.dynamic.flowtab").exceptionOrNull()

            assertIs<PluginLookupException>(failure)
            assertEquals(shared, failure.cause)
            assertTrue(shared.suppressedExceptions.isEmpty(), "the shared cause must not suppress itself")
        }

    @Test
    fun `the message stays short even when the underlying error is enormous`() =
        runTest {
            // A kotlinx malformed-input error appends the whole offending document, and this message
            // goes into a wizard row.
            val huge = IllegalStateException("Field 'versionRange' is required\n" + "x".repeat(50_000))
            val manager = PluginRepositoryManager()
            manager.addRepository(FakeRepository(id = "store", isLocal = false, answer = Result.failure(huge)))

            val failure = manager.getPlugin("ai.rever.boss.plugin.dynamic.flowtab").exceptionOrNull()

            assertNotNull(failure)
            assertTrue(
                failure.message!!.length < 300,
                "message reaches the UI, length was ${failure.message!!.length}",
            )
            assertEquals(huge, failure.cause, "the full detail must still be available for the log")
        }

    @Test
    fun `shortFailureReason clips a long first line and keeps a short one`() {
        assertEquals("boom", shortFailureReason(IllegalStateException("boom")))
        // First line only: the rest of a kotlinx error is the document it choked on.
        assertEquals("first", shortFailureReason(IllegalStateException("first\nsecond")))
        val clipped = shortFailureReason(IllegalStateException("y".repeat(5_000)))
        assertTrue(clipped.length < 200, "clipped to ${clipped.length}")
        assertTrue(clipped.endsWith("…"), "a clipped reason should show that it was cut: $clipped")
        // A throwable with no message at all still yields something nameable.
        assertEquals("IllegalStateException", shortFailureReason(IllegalStateException(null as String?)))
        // As does one whose message is blank, which would otherwise render as an empty reason.
        assertEquals("IllegalStateException", shortFailureReason(IllegalStateException("   ")))
    }

    private fun pluginInfo(pluginId: String) =
        PluginInfo(
            pluginId = pluginId,
            displayName = "Flow",
            version = "1.0.14",
            description = "Visual flow builder",
        )

    /** Answers every lookup with one canned [Result]; nothing else is exercised. */
    private class FakeRepository(
        override val id: String,
        override val isLocal: Boolean,
        private val answer: Result<PluginInfo?>,
    ) : PluginRepository {
        override val name: String = id
        override val isAvailable: Boolean = true

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> = answer

        override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(emptyList())

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> = Result.success(emptyList())

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
            onProgress: ((Float) -> Unit)?,
        ): Result<String> = Result.failure(UnsupportedOperationException())

        override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
    }

    /** Throws out of `getPlugin` instead of returning `Result.failure`, which the interface allows. */
    private class ThrowingRepository(
        override val id: String,
    ) : PluginRepository {
        override val name: String = id
        override val isLocal: Boolean = false
        override val isAvailable: Boolean = true

        override suspend fun getPlugin(pluginId: String): Result<PluginInfo?> = error("this repository throws")

        override suspend fun listPlugins(): Result<List<PluginInfo>> = Result.success(emptyList())

        override suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult> =
            Result.failure(UnsupportedOperationException())

        override suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>> = Result.success(emptyList())

        override suspend fun downloadPlugin(
            pluginId: String,
            version: String?,
            targetPath: String,
            onProgress: ((Float) -> Unit)?,
        ): Result<String> = Result.failure(UnsupportedOperationException())

        override fun getDownloadProgress(pluginId: String): Flow<Float>? = null

        override suspend fun refresh(): Result<Unit> = Result.success(Unit)
    }
}
