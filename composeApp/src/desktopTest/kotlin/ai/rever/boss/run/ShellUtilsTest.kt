package ai.rever.boss.run

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Locks down [ShellUtils]: the quoting/escaping/separator logic every runner
 * command and "open terminal here" flow is built from.
 *
 * CI runs this suite on Linux, macOS AND Windows, and [ShellUtils.isWindows] is
 * fixed at class-load from the real OS, so the single-argument expectations are
 * written per platform: the POSIX branch is verified on Unix runners and the
 * PowerShell branch on Windows runners.
 *
 * Because that leaves each branch covered by only one CI leg, the second half of
 * this suite drives the platform explicitly through the `forWindows` overloads, so
 * both branches of [ShellUtils.escapeForDoubleQuotes], [ShellUtils.separatorFor]
 * and [ShellUtils.buildCommandWithWorkingDirectory] also run on every host. The
 * host-resolved expectations above stay: they are the only check that
 * [ShellUtils.isWindows] wires this OS to the right branch in the first place.
 *
 * [ShellUtils.chainCommands] has no platform-explicit overload — its only
 * platform-dependent input is the separator, covered on both branches here.
 */
class ShellUtilsTest {
    private val win = ShellUtils.isWindows
    private val sep = ShellUtils.commandSeparator

    private fun expect(
        unix: String,
        windows: String,
    ): String = if (win) windows else unix

    // ==================== commandSeparator ====================

    @Test
    fun `separator is and-and on unix, semicolon on windows powershell`() {
        assertEquals(expect(unix = " && ", windows = "; "), sep)
    }

    // ==================== escapeForDoubleQuotes: pass-through ====================

    @Test
    fun `plain text is unchanged`() {
        assertEquals("hello-world_123", ShellUtils.escapeForDoubleQuotes("hello-world_123"))
    }

    @Test
    fun `spaces are unchanged - the caller adds the surrounding quotes`() {
        assertEquals("My Project Dir", ShellUtils.escapeForDoubleQuotes("My Project Dir"))
    }

    @Test
    fun `unicode is unchanged`() {
        assertEquals("docs/日本語 déjà ✨", ShellUtils.escapeForDoubleQuotes("docs/日本語 déjà ✨"))
    }

    @Test
    fun `empty string stays empty`() {
        assertEquals("", ShellUtils.escapeForDoubleQuotes(""))
    }

    @Test
    fun `semicolon ampersand and pipe pass through - literal inside double quotes`() {
        // The function only claims safety INSIDE double quotes, where these are literal.
        assertEquals("a; b && c | d", ShellUtils.escapeForDoubleQuotes("a; b && c | d"))
    }

    // ==================== escapeForDoubleQuotes: escaped metacharacters ====================

    @Test
    fun `double quote is escaped`() {
        assertEquals(
            expect(unix = "say \\\"hi\\\"", windows = "say `\"hi`\""),
            ShellUtils.escapeForDoubleQuotes("say \"hi\""),
        )
    }

    @Test
    fun `dollar is escaped to block variable expansion`() {
        assertEquals(
            expect(unix = "\\\$HOME", windows = "`\$HOME"),
            ShellUtils.escapeForDoubleQuotes("\$HOME"),
        )
    }

    @Test
    fun `command substitution via dollar-paren is neutralized`() {
        assertEquals(
            expect(unix = "\\\$(rm -rf ~)", windows = "`\$(rm -rf ~)"),
            ShellUtils.escapeForDoubleQuotes("\$(rm -rf ~)"),
        )
    }

    @Test
    fun `backtick is escaped to block command substitution`() {
        assertEquals(
            expect(unix = "a\\`whoami\\`b", windows = "a``whoami``b"),
            ShellUtils.escapeForDoubleQuotes("a`whoami`b"),
        )
    }

    @Test
    fun `backslash is doubled on unix and literal on powershell`() {
        // PowerShell's escape character is the backtick; backslash is a literal there.
        assertEquals(
            expect(unix = "C:\\\\Temp", windows = "C:\\Temp"),
            ShellUtils.escapeForDoubleQuotes("C:\\Temp"),
        )
    }

    @Test
    fun `exclamation is escaped on unix only - history expansion`() {
        assertEquals(
            expect(unix = "deploy\\!", windows = "deploy!"),
            ShellUtils.escapeForDoubleQuotes("deploy!"),
        )
    }

    @Test
    fun `escape ordering - a backslash-quote pair does not double-escape`() {
        // Unix: backslash is escaped first (\ -> \\), then the quote (" -> \"),
        // so the two-char input backslash+quote becomes \\ + \" (four chars).
        assertEquals(
            expect(unix = "\\\\\\\"", windows = "\\`\""),
            ShellUtils.escapeForDoubleQuotes("\\\""),
        )
    }

    // ============ platform-explicit overloads: both branches on any host ============
    //
    // The tests above can only ever reach the host's own branch. These pass the platform
    // in, so the POSIX *and* the PowerShell branch are covered on every runner — dropping
    // the Windows CI leg cannot silently untest a branch.

    private fun unix(str: String): String = ShellUtils.escapeForDoubleQuotes(str, forWindows = false)

    private fun powershell(str: String): String = ShellUtils.escapeForDoubleQuotes(str, forWindows = true)

    @Test
    fun `single-argument overload delegates to the host branch`() {
        val nasty = "a\"b\\c\$d`e!f"
        assertEquals(
            ShellUtils.escapeForDoubleQuotes(nasty, forWindows = win),
            ShellUtils.escapeForDoubleQuotes(nasty),
        )
    }

    @Test
    fun `separatorFor covers both branches and backs the host property`() {
        assertEquals(" && ", ShellUtils.separatorFor(forWindows = false))
        assertEquals("; ", ShellUtils.separatorFor(forWindows = true))
        // commandSeparator is just this function applied to the host platform.
        assertEquals(ShellUtils.separatorFor(forWindows = win), sep)
    }

    @Test
    fun `buildCommandWithWorkingDirectory composes escaping and separator on both branches`() {
        assertEquals(
            "cd \"/data/\\\$USER files\" && ls",
            ShellUtils.buildCommandWithWorkingDirectory("ls", "/data/\$USER files", forWindows = false),
        )
        assertEquals(
            "cd \"C:\\Users\\dev\\My `\$Project\"; ls",
            ShellUtils.buildCommandWithWorkingDirectory("ls", "C:\\Users\\dev\\My \$Project", forWindows = true),
        )
    }

    @Test
    fun `buildCommandWithWorkingDirectory skips the cd on both branches when there is no directory`() {
        for (forWindows in listOf(false, true)) {
            assertEquals("ls", ShellUtils.buildCommandWithWorkingDirectory("ls", null, forWindows))
            assertEquals("ls", ShellUtils.buildCommandWithWorkingDirectory("ls", "  ", forWindows))
        }
    }

    @Test
    fun `two-argument buildCommandWithWorkingDirectory delegates to the host branch`() {
        val dir = "/data/\$USER files"
        assertEquals(
            ShellUtils.buildCommandWithWorkingDirectory("ls", dir, forWindows = win),
            ShellUtils.buildCommandWithWorkingDirectory("ls", dir),
        )
    }

    @Test
    fun `posix branch escapes quote, backslash, dollar, backtick and bang`() {
        assertEquals("\\\"", unix("\""))
        assertEquals("C:\\\\Temp", unix("C:\\Temp"))
        assertEquals("\\\$HOME", unix("\$HOME"))
        assertEquals("a\\`whoami\\`b", unix("a`whoami`b"))
        assertEquals("deploy\\!", unix("deploy!"))
    }

    @Test
    fun `powershell branch escapes quote, dollar and backtick, leaving backslash and bang literal`() {
        assertEquals("`\"", powershell("\""))
        assertEquals("C:\\Temp", powershell("C:\\Temp"))
        assertEquals("`\$HOME", powershell("\$HOME"))
        assertEquals("a``whoami``b", powershell("a`whoami`b"))
        assertEquals("deploy!", powershell("deploy!"))
    }

    @Test
    fun `both branches pass through text with nothing to escape`() {
        for (input in listOf("", "hello-world_123", "My Project Dir", "docs/日本語 déjà ✨", "a; b | c")) {
            assertEquals(input, unix(input))
            assertEquals(input, powershell(input))
        }
    }

    @Test
    fun `posix ordering - escape characters inserted later are not re-escaped`() {
        // Backslash is doubled first, so the backslashes added for " and $ stay single.
        assertEquals("\\\\\\\"", unix("\\\""))
        assertEquals("\\\\\\\$", unix("\\\$"))
    }

    @Test
    fun `powershell ordering - the backtick added for a quote is not doubled`() {
        // Backtick is doubled first; the backtick that escapes the quote comes after.
        assertEquals("```\"", powershell("`\""))
        assertEquals("\\`\"", powershell("\\\""))
    }

    @Test
    fun `command substitution is neutralized on both branches`() {
        assertEquals("\\\$(rm -rf ~)", unix("\$(rm -rf ~)"))
        assertEquals("`\$(rm -rf ~)", powershell("\$(rm -rf ~)"))
    }

    @Test
    fun `a string mixing every metacharacter escapes correctly on both branches`() {
        val nasty = "a\"b\\c\$d`e!f"
        assertEquals("a\\\"b\\\\c\\\$d\\`e\\!f", unix(nasty))
        assertEquals("a`\"b\\c`\$d``e!f", powershell(nasty))
    }

    // ==================== buildCommandWithWorkingDirectory ====================

    @Test
    fun `null working directory returns the command untouched`() {
        assertEquals("ls -la", ShellUtils.buildCommandWithWorkingDirectory("ls -la", null))
    }

    @Test
    fun `blank working directory returns the command untouched`() {
        assertEquals("ls -la", ShellUtils.buildCommandWithWorkingDirectory("ls -la", ""))
        assertEquals("ls -la", ShellUtils.buildCommandWithWorkingDirectory("ls -la", "   "))
    }

    @Test
    fun `working directory is quoted and chained with the platform separator`() {
        assertEquals(
            "cd \"/Users/dev/My Project\"${sep}ls -la",
            ShellUtils.buildCommandWithWorkingDirectory("ls -la", "/Users/dev/My Project"),
        )
    }

    @Test
    fun `working directory with a dollar sign cannot expand`() {
        assertEquals(
            expect(
                unix = "cd \"/data/\\\$USER files\" && ls",
                windows = "cd \"/data/`\$USER files\"; ls",
            ),
            ShellUtils.buildCommandWithWorkingDirectory("ls", "/data/\$USER files"),
        )
    }

    // ==================== chainCommands ====================

    @Test
    fun `chainCommands joins with the platform separator`() {
        assertEquals("a${sep}b${sep}c", ShellUtils.chainCommands("a", "b", "c"))
    }

    @Test
    fun `chainCommands with a single command returns it unchanged`() {
        assertEquals("./gradlew build", ShellUtils.chainCommands("./gradlew build"))
    }

    @Test
    fun `chainCommands with no commands yields an empty string`() {
        assertEquals("", ShellUtils.chainCommands())
    }
}
