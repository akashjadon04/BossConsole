package ai.rever.boss.updater

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.io.File
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

private val logger = BossLogger.forComponent("UpdaterDirectories")

private val OWNER_ONLY_DIR_PERMISSIONS =
    setOf(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE,
        PosixFilePermission.OWNER_EXECUTE,
    )

private val isWindows: Boolean
    get() = System.getProperty("os.name").lowercase().contains("win")

/**
 * Create (or adopt) an updater working directory that only the current user can
 * enter, and **fail closed** if that cannot be guaranteed.
 *
 * Both updater directories live under the shared platform temp directory, and
 * both hold files that are later run or installed with elevated privileges: the
 * helper scripts executed via `sudo`/`pkexec`, and the downloaded `.dmg`/`.msi`/
 * `.deb` between checksum verification and install. If another local user
 * pre-created the directory, a silently-failing `mkdirs()` + best-effort chmod
 * would leave us writing those files into a directory we do not own — a TOCTOU
 * window no checksum can close. So this throws instead of warning, matching
 * [UpdatePathValidator]'s posture.
 *
 * On Windows this is a plain `mkdirs()`: `%TEMP%` is already per-user, and POSIX
 * permissions do not apply.
 *
 * @throws SecurityException if an owner-only directory cannot be guaranteed.
 */
internal fun createRestrictedDir(dir: File): File {
    // %TEMP% is already per-user on Windows, and POSIX permissions do not apply.
    if (isWindows) return createWindowsDir(dir)

    val path = dir.toPath()
    try {
        Files.createDirectories(path.parent)
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(OWNER_ONLY_DIR_PERMISSIONS))
    } catch (expected: FileAlreadyExistsException) {
        // Someone got there first - us on a previous run, or another user. Which one
        // it was is exactly what verifyOwnedDirectory decides.
        logger.debug(
            LogCategory.SYSTEM,
            "Updater directory already exists - verifying ownership",
            mapOf("dir" to dir.absolutePath, "reason" to (expected.message ?: "exists")),
        )
        verifyOwnedDirectory(path)
    } catch (e: java.io.IOException) {
        throw SecurityException("Could not create owner-only updater directory ${dir.absolutePath}", e)
    }

    return dir
}

/** Fail closed with a consistent message shape. */
private fun requireSecure(
    condition: Boolean,
    message: () -> String,
) {
    if (!condition) throw SecurityException(message())
}

private fun createWindowsDir(dir: File): File {
    requireSecure(dir.isDirectory || dir.mkdirs()) { "Could not create updater directory: ${dir.absolutePath}" }
    return dir
}

/**
 * An existing directory is only usable if it is a real directory (not a symlink
 * someone else planted), owned by us, and reachable by nobody else.
 */
private fun verifyOwnedDirectory(path: Path) {
    requireSecure(!Files.isSymbolicLink(path)) { "Updater directory is a symlink - refusing to use it: $path" }
    requireSecure(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        "Updater directory path is not a directory: $path"
    }

    val expectedOwner = System.getProperty("user.name")
    val actualOwner =
        try {
            Files.getOwner(path, LinkOption.NOFOLLOW_LINKS)?.name
        } catch (e: java.io.IOException) {
            throw SecurityException("Could not read owner of updater directory $path", e)
        }
    requireSecure(expectedOwner == null || actualOwner == null || actualOwner == expectedOwner) {
        "Updater directory $path is owned by '$actualOwner', not '$expectedOwner'"
    }

    // Adopt an existing directory only if we can actually lock it down.
    try {
        Files.setPosixFilePermissions(path, OWNER_ONLY_DIR_PERMISSIONS)
    } catch (expected: UnsupportedOperationException) {
        // Non-POSIX filesystem on a non-Windows OS: nothing further to enforce.
        logger.debug(
            LogCategory.SYSTEM,
            "Filesystem does not support POSIX permissions",
            mapOf("dir" to path.toString(), "reason" to (expected.message ?: "unsupported")),
        )
        return
    } catch (e: java.io.IOException) {
        throw SecurityException("Could not restrict updater directory $path to its owner", e)
    }

    val permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)
    requireSecure(permissions == OWNER_ONLY_DIR_PERMISSIONS) {
        "Updater directory $path is not owner-only: $permissions"
    }
}
