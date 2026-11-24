package ai.rever.boss.plugin.repository

import kotlinx.coroutines.flow.Flow

/**
 * Interface for plugin repositories.
 *
 * A repository provides access to plugins, either from local storage
 * or remote plugin stores.
 */
interface PluginRepository {
    /**
     * Unique identifier for this repository.
     */
    val id: String

    /**
     * Human-readable name for this repository.
     */
    val name: String

    /**
     * Whether this is a local repository.
     */
    val isLocal: Boolean

    /**
     * Whether this repository is currently available.
     */
    val isAvailable: Boolean

    /**
     * List all plugins in this repository.
     *
     * @return List of available plugins
     */
    suspend fun listPlugins(): Result<List<PluginInfo>>

    /**
     * Search for plugins matching the given filter.
     *
     * @param filter Search filter
     * @return Search results
     */
    suspend fun searchPlugins(filter: PluginSearchFilter): Result<PluginSearchResult>

    /**
     * Get detailed information about a specific plugin.
     *
     * @param pluginId The plugin ID
     * @return Plugin info, or null if not found
     */
    suspend fun getPlugin(pluginId: String): Result<PluginInfo?>

    /**
     * Get all available versions of a plugin.
     *
     * @param pluginId The plugin ID
     * @return List of available versions (sorted newest first)
     */
    suspend fun getPluginVersions(pluginId: String): Result<List<PluginInfo>>

    /**
     * Download a plugin JAR to the specified path.
     *
     * @param pluginId The plugin ID
     * @param version The version to download (null for latest)
     * @param targetPath Path to save the downloaded JAR
     * @param onProgress Called with the download fraction (0.0 to 1.0) as bytes
     *   arrive. Pushed rather than polled through [getDownloadProgress]: that
     *   flow only exists once a download has started, so a caller wanting to
     *   show progress has to race it into existence. Every host install path
     *   funnels through here, which is why the callback is here and not on one
     *   of them.
     * @return Result with the path to the downloaded file
     */
    suspend fun downloadPlugin(
        pluginId: String,
        version: String? = null,
        targetPath: String,
        onProgress: ((Float) -> Unit)? = null,
    ): Result<String>

    /**
     * Get download progress as a flow.
     *
     * @param pluginId The plugin ID being downloaded
     * @return Flow of download progress (0.0 to 1.0)
     */
    fun getDownloadProgress(pluginId: String): Flow<Float>?

    /**
     * Refresh the repository's plugin list.
     *
     * For remote repositories, this fetches the latest data from the server.
     * For local repositories, this rescans the plugin directory.
     *
     * @return Result indicating success or failure
     */
    suspend fun refresh(): Result<Unit>
}

/**
 * Exception thrown when a repository operation fails.
 */
class RepositoryException(
    message: String,
    val repositoryId: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception thrown when a plugin is not found.
 */
class PluginNotFoundException(
    val pluginId: String,
    val repositoryId: String,
) : Exception("Plugin not found: $pluginId in repository: $repositoryId")

/**
 * Exception thrown when a download fails.
 */
class DownloadException(
    message: String,
    val pluginId: String,
    val repositoryId: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * No repository could be *asked* about a plugin, as distinct from every repository answering "no".
 *
 * `PluginRepositoryManager.getPlugin` used to collapse both into `success(null)`, so a repository
 * that threw was indistinguishable from a plugin that is genuinely unpublished. The first-run wizard
 * then told the user "Tool not found in repository" about a plugin whose store row was present and
 * valid - the actual cause was a decode failure on one dependency entry, and it took reading a stack
 * trace in the log to find that out.
 *
 * The [message] is deliberately SHORT and the real error is kept only as `cause`. This message
 * reaches the wizard's failure list, and the underlying errors are not fit for that: kotlinx appends
 * the entire offending document to a malformed-input error, which would put a whole plugin payload in
 * a UI row. Callers log the cause and show the message.
 */
class PluginLookupException(
    val pluginId: String,
    cause: Throwable,
) : Exception("Could not query the plugin repository for $pluginId: ${shortFailureReason(cause)}", cause)

/**
 * A one-line description of [cause] fit for a UI row.
 *
 * Takes the first line and clips it, because the messages behind a failed lookup are unbounded - a
 * kotlinx serialization error carries the whole JSON document it choked on.
 *
 * Public because more than one caller puts a repository failure in front of a user, and each one
 * inventing its own clipping is how one of them ends up without any. `PluginStoreVersionBridge` builds
 * its own `Unavailable` message rather than surfacing an exception, so it shares this instead.
 */
fun shortFailureReason(cause: Throwable): String {
    val firstLine =
        cause.message
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: cause::class.simpleName
            ?: "unknown error"
    return if (firstLine.length <= MAX_REASON_LENGTH) firstLine else firstLine.take(MAX_REASON_LENGTH) + "…"
}

private const val MAX_REASON_LENGTH = 160
