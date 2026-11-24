package ai.rever.boss.services.importer.browser

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager

/**
 * Opens a browser's SQLite database safely.
 *
 * A running browser holds its databases open, and on some platforms locks them
 * outright, so reading in place either fails or blocks. Everything here works on
 * a private copy: the browser stays untouched and can keep writing while the
 * import reads.
 *
 * The copy includes the `-wal` and `-shm` sidecars when present — without them
 * a database in WAL mode reads back as an older snapshot, silently missing
 * whatever the browser wrote most recently.
 */
internal object SqliteSnapshot {
    /**
     * Copy [source] to a temp file, run [block] against it, then delete the copy.
     *
     */
    fun <T> read(
        source: File,
        block: (Connection) -> T,
    ): T {
        // A private directory, not bare temp files: createTempFile is 0600 but
        // Files.copy creates the -wal/-shm sidecars with default attributes
        // (0644 under a typical umask), and for Login Data the WAL holds
        // usernames, origin URLs and recently written encrypted blobs.
        // createTempDirectory is 0700 on POSIX; on Windows it inherits the
        // parent's ACL, which is why the password path stays macOS/Linux only.
        val workDir = Files.createTempDirectory("boss-import-")
        val temp = workDir.resolve("snapshot.sqlite")

        try {
            Files.copy(source.toPath(), temp, StandardCopyOption.REPLACE_EXISTING)
            listOf("-wal", "-shm").forEach { suffix ->
                val extra = File(source.absolutePath + suffix)
                if (extra.isFile) {
                    Files.copy(
                        extra.toPath(),
                        workDir.resolve("snapshot.sqlite$suffix"),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }

            // Explicit driver load: the host shades dependencies, so relying on
            // ServiceLoader discovery alone is fragile.
            Class.forName("org.sqlite.JDBC")

            // Plain path rather than a file: URI — a Windows path contains
            // backslashes and a drive letter, which URI parsing rejects.
            //
            // Opened read-write on purpose. This is a disposable private copy,
            // so there is nothing to protect from a write; read-only would stop
            // SQLite replaying a -wal left behind by a crashed browser, which is
            // exactly the state this class has to cope with.
            return DriverManager
                .getConnection("jdbc:sqlite:${temp.toAbsolutePath()}")
                .use(block)
        } finally {
            runCatching { workDir.toFile().deleteRecursively() }
        }
    }
}
