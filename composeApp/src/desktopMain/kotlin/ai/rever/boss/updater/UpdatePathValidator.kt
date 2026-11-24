package ai.rever.boss.updater

/**
 * The single set of rules for paths and filenames that reach the update
 * installers, shared by [UpdateScriptGenerator], [UpdateInstaller] and the
 * download path, so a suspicious artifact is refused no matter which entry point
 * ran (Issue #37). Everything here fails closed: it throws rather than logging.
 *
 * The rules are split by *where in the path* the character sits, because one
 * denylist cannot be right for the whole string:
 *
 * - **Never legitimate anywhere** in an update path, and not something quoting
 *   should have to save us from: NUL, newlines, `..`, `$`, a backtick, `;`, `|`.
 *   [validatePath] rejects these over the entire path.
 * - **Legitimate in a directory name, denied in the artifact name**: `&`, `%`,
 *   `^`, `!`. Windows account names permit all four, so denylisting them over the
 *   whole path bricked auto-update permanently for a user named `Bob!` or `A&B` -
 *   with a message that reads like an attack report. In a directory component they
 *   are neutralised by `escapeShellArg` (single quotes) / `escapeWindowsArg`
 *   (quotes plus doubled `%`), and the directory part is ours: the OS temp
 *   directory plus a constant. In the filename they are refused, because that
 *   component comes from the remote release catalog and is what gets interpolated
 *   into the generated script.
 */
internal object UpdatePathValidator {
    /**
     * The filename component of [path], treating both `/` and `\` as separators so
     * a Windows-shaped path splits correctly whatever host this runs on.
     */
    fun fileNameComponent(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

    /**
     * Validate a whole path against the characters that are never legitimate
     * anywhere in it.
     *
     * @throws SecurityException if [path] contains dangerous characters
     */
    fun validatePath(
        path: String,
        description: String,
    ) {
        // Check for null bytes (can bypass path checks)
        if (path.contains('\u0000')) {
            throw SecurityException("$description contains null byte - possible directory traversal attack")
        }

        // Check for newlines (could inject additional commands)
        if (path.contains('\n') || path.contains('\r')) {
            throw SecurityException("$description contains newline characters - possible script injection")
        }

        // Legitimate update paths are always absolute and never use ..
        if (path.contains("..")) {
            throw SecurityException("$description contains path traversal sequence '..' - rejected for security")
        }

        // Command substitution and pipes/sequencing: no legitimate directory or
        // artifact name contains these, so they are refused over the whole path
        // rather than relying on the escapers.
        if (path.contains('$') || path.contains('`')) {
            throw SecurityException("$description contains shell metacharacters - possible command injection")
        }
        if (path.contains(";") || path.contains("|")) {
            throw SecurityException("$description contains command separator characters - rejected for security")
        }
    }

    /**
     * Validate a filename component with the full denylist. For anything the
     * release catalog supplied.
     *
     * @throws SecurityException if [fileName] contains dangerous characters
     */
    fun validateFileName(
        fileName: String,
        description: String,
    ) {
        validatePath(fileName, description)

        // Background/command separator. Legal in a Windows account name, never in
        // an artifact name.
        if (fileName.contains("&")) {
            throw SecurityException("$description contains command separator characters - rejected for security")
        }

        // Windows batch metacharacters: variable expansion (%VAR%), escape (^) and
        // delayed expansion (!VAR!). Also legal in account names, never in an
        // artifact name.
        if (fileName.contains("%") || fileName.contains("^") || fileName.contains("!")) {
            throw SecurityException("$description contains Windows batch metacharacters - rejected for security")
        }
    }

    /**
     * Validate a full path plus, with the stricter rules, its filename component.
     * The entry point for the script generators.
     *
     * @throws SecurityException if either part is dangerous
     */
    fun validatePathAndFileName(
        path: String,
        description: String,
    ) {
        validatePath(path, description)
        validateFileName(fileNameComponent(path), description)
    }
}
