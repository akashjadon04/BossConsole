package ai.rever.boss.dashboard

import ai.rever.boss.plugin.pathutils.BossDirectories
import ai.rever.boss.utils.extractFileName
import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

private val recentFilesLogger = BossLogger.forComponent("RecentFilesManager")

/**
 * Data class representing a recently opened file.
 */
@Serializable
data class RecentFile(
    val path: String,
    val name: String,
    val lastOpened: Long,
    val projectPath: String? = null,
)

/**
 * Container for recent files data with serialization support.
 */
@Serializable
data class RecentFilesData(
    val files: List<RecentFile> = emptyList(),
)

/**
 * Manages recently opened files for the Dashboard.
 * Persists to ~/.boss/recent-files.json
 *
 * Thread-safe: All file I/O operations run on Dispatchers.IO.
 * Uses StateFlow for reactive UI updates.
 */
object RecentFilesManager {
    private const val MAX_FILES = 20
    private const val SAVE_DEBOUNCE_MS = 5000L // Debounce saves to max once per 5 seconds
    private val settingsFile = BossDirectories.resolve("recent-files.json")
    private val json =
        Json {
            prettyPrint = false
            ignoreUnknownKeys = true
            encodeDefaults = false
        }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var saveJob: Job? = null

    /**
     * Every recorded file, including ones not currently on disk. **This is what is persisted.**
     *
     * Split from [recentFiles] because the displayed list is filtered by `File.exists()`, and that
     * is false for an unmounted volume or a disconnected share - normal at login, which is when
     * the prune runs. Persisting the filtered list would turn "not here right now" into permanent
     * loss: the first `recordFileOpen` after launch calls `scheduleSave()`, which serialises
     * whatever this manager holds, so hiding and saving cannot be the same list.
     */
    private val _allFiles = MutableStateFlow<List<RecentFile>>(emptyList())

    private val _recentFiles = MutableStateFlow<List<RecentFile>>(emptyList())

    /** The displayed list: [_allFiles] minus anything not on disk right now. */
    val recentFiles: StateFlow<List<RecentFile>> = _recentFiles.asStateFlow()

    /**
     * Replace the recorded list and re-derive the displayed one.
     *
     * Every mutation goes through here so the two can never drift - the defect being avoided is a
     * caller updating the display and the save then writing the display back.
     */
    private fun setFiles(files: List<RecentFile>) {
        _allFiles.value = files
        _recentFiles.value = visibleFiles(files) { fileExists(it) }
    }

    init {
        scope.launch {
            loadAsync()
        }
    }

    /**
     * Load recent files from disk asynchronously.
     */
    private suspend fun loadAsync() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()

                if (settingsFile.exists()) {
                    val content = settingsFile.readText()
                    val data = json.decodeFromString<RecentFilesData>(content)
                    // Hidden, not pruned: a file that is not on disk stops being offered (it
                    // would open an empty editor - fileExists existed for exactly this and had no
                    // callers) but stays in the recorded list, so an unmounted volume coming back
                    // brings its entries with it. See _allFiles.
                    setFiles(data.files)
                    val present = _recentFiles.value
                    recentFilesLogger.debug(
                        LogCategory.FILE,
                        "Loaded recent files",
                        mapOf("count" to present.size, "hidden" to (data.files.size - present.size)),
                    )
                }
            } catch (e: Exception) {
                recentFilesLogger.warn(LogCategory.FILE, "Error loading recent files", error = e)
            }
        }

    /**
     * Save recent files to disk with debouncing.
     * Cancels any pending save and schedules a new one after SAVE_DEBOUNCE_MS.
     */
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob =
            scope.launch {
                delay(SAVE_DEBOUNCE_MS)
                saveImmediately()
            }
    }

    /**
     * Immediately save recent files to disk (bypasses debounce).
     */
    private suspend fun saveImmediately() =
        withContext(Dispatchers.IO) {
            try {
                settingsFile.parentFile?.mkdirs()
                // The recorded list, never the filtered view; see _allFiles.
                val data = RecentFilesData(files = _allFiles.value)
                val content = json.encodeToString(RecentFilesData.serializer(), data)
                settingsFile.writeText(content)
            } catch (e: Exception) {
                recentFilesLogger.warn(LogCategory.FILE, "Error saving recent files", error = e)
            }
        }

    /**
     * Record a file open event.
     * Moves the file to the top if already present, otherwise adds it.
     * Maintains max file limit.
     *
     * @param filePath Absolute path to the file
     * @param projectPath Optional project path the file belongs to
     */
    fun recordFileOpen(
        filePath: String,
        projectPath: String? = null,
    ) {
        scope.launch {
            val fileName = filePath.extractFileName()
            val newFile =
                RecentFile(
                    path = filePath,
                    name = fileName,
                    lastOpened = System.currentTimeMillis(),
                    projectPath = projectPath,
                )

            // Remove existing entry for this path and add to front
            // Over the recorded list, not the displayed one, so opening a file does not drop
            // entries that are merely on an absent volume.
            val currentFiles = _allFiles.value.toMutableList()
            currentFiles.removeAll { it.path == filePath }
            currentFiles.add(0, newFile)

            // Trim to max size
            setFiles(currentFiles.take(MAX_FILES))
            scheduleSave()
        }
    }

    /**
     * Remove a specific file from recent history.
     */
    fun removeFile(filePath: String) {
        scope.launch {
            setFiles(_allFiles.value.filter { it.path != filePath })
            scheduleSave()
        }
    }

    /**
     * Clear all recent files.
     */
    fun clearAll() {
        scope.launch {
            setFiles(emptyList())
            scheduleSave()
        }
    }

    /**
     * Check if a file still exists on disk.
     */
    fun fileExists(filePath: String): Boolean = File(filePath).exists()
}

/**
 * The displayed subset of [all]: entries whose file is present according to [exists].
 *
 * Pure and separate so the hide-versus-prune rule is testable without driving the singleton's file
 * I/O. The other half of that rule - that the **recorded** list is what gets persisted - is
 * structural rather than tested: `saveImmediately` serialises `_allFiles`, and `setFiles` is the
 * only writer of either flow. If a future change makes `saveImmediately` read `_recentFiles`, an
 * absent volume becomes permanent deletion again and nothing here will catch it.
 */
internal fun visibleFiles(
    all: List<RecentFile>,
    exists: (path: String) -> Boolean,
): List<RecentFile> = all.filter { exists(it.path) }
