package ai.rever.boss.cli

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [CLISecurityValidator.isValidOpenTargetPath], the check applied to a
 * file BOSS is about to read into the editor.
 *
 * It exists because `isValidPath` - the right rules for a path that ends up in a
 * shell command - refused ordinary filenames. A file called `Q&A notes.md` failed
 * the shell-metacharacter test, so double-clicking it logged "Invalid file path
 * (security check failed)" and nothing opened. No shell is involved on this path,
 * so those characters are just characters.
 */
class OpenTargetPathTest {
    @Test
    fun `accepts filenames that isValidPath refused`() {
        // Every one of these is a legal filename and every one was rejected.
        listOf(
            "/Users/me/Documents/Q&A notes.md",
            "/Users/me/scripts/pay\$.sh",
            "/Users/me/notes;draft.md",
            "/Users/me/a|b.txt",
            "/Users/me/back`tick`.md",
            "/Users/me/notes..draft.md",
        ).forEach { path ->
            assertTrue(CLISecurityValidator.isValidOpenTargetPath(path), "should accept $path")
            // The contrast is the point of having two functions.
            assertFalse(CLISecurityValidator.isValidPath(path), "isValidPath was expected to refuse $path")
        }
    }

    @Test
    fun `accepts an ordinary absolute path`() {
        assertTrue(CLISecurityValidator.isValidOpenTargetPath("/Users/me/project/src/Main.kt"))
        // Parentheses are fine for both validators; listed here so the contrast
        // above stays a list of characters isValidPath actually rejects.
        assertTrue(CLISecurityValidator.isValidOpenTargetPath("/Users/me/Projects (2026)/README.md"))
    }

    @Test
    fun `accepts a path containing dot-dot, which canonicalises away`() {
        // `..` was refused as a traversal defence, which it never was on this
        // path: the caller may pass any absolute path anyway, so the segment adds
        // no reach. Canonicalising is both stricter and correct.
        assertTrue(CLISecurityValidator.isValidOpenTargetPath("/Users/me/project/../project/README.md"))
    }

    @Test
    fun `refuses a NUL byte`() {
        // Truncates the path in any native call underneath, so the file that gets
        // opened is not the file that was checked.
        assertFalse(CLISecurityValidator.isValidOpenTargetPath("/Users/me/notes.md\u0000.png"))
    }

    @Test
    fun `refuses blank input`() {
        assertFalse(CLISecurityValidator.isValidOpenTargetPath(""))
        assertFalse(CLISecurityValidator.isValidOpenTargetPath("   "))
    }

    @Test
    fun `the shell-facing validator is unchanged`() {
        // The terminal and folder paths still use isValidPath, and loosening it
        // was never the intent - only splitting the two apart.
        assertFalse(CLISecurityValidator.isValidPath("/tmp/a;rm -rf /"))
        assertFalse(CLISecurityValidator.isValidPath("/tmp/../etc/passwd"))
        assertTrue(CLISecurityValidator.isValidPath("/Users/me/project/src/Main.kt"))
    }
}
