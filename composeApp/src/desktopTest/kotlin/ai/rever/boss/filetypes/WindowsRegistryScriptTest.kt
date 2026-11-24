package ai.rever.boss.filetypes

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the generated `.reg` script and the `reg query` parser.
 *
 * Both are pure string work driving a platform feature nobody developing on macOS
 * will notice breaking, and the failure is silent: an association that exists but
 * launches nothing. The `shell\open\command` value in particular is the single
 * write that makes a double-clicked file reach BOSS.
 */
class WindowsRegistryScriptTest {
    private val exe = """C:\Program Files\BOSS\BOSS.exe"""

    private fun script(vararg extensions: String): String {
        // Block body, not an expression body: ktlintFormat collapses the latter
        // back onto one line, past detekt's 120-column limit.
        return WindowsRegistryScript.buildScript(extensions.toList(), exe, "Markdown")
    }

    // ---- escaping ----

    @Test
    fun `backslashes are doubled and quotes escaped`() {
        assertEquals("""C:\\Program Files\\BOSS\\BOSS.exe""", WindowsRegistryScript.regEscape(exe))
        assertEquals("""a\"b""", WindowsRegistryScript.regEscape("""a"b"""))
        // Order matters: escaping quotes first and then backslashes would double
        // the backslash the quote escape just added.
        assertEquals("""\\\"""", WindowsRegistryScript.regEscape("""\""""))
    }

    @Test
    fun `a path with no special characters is unchanged`() {
        assertEquals("C:/BOSS/BOSS.exe", WindowsRegistryScript.regEscape("C:/BOSS/BOSS.exe"))
    }

    // ---- the script ----

    @Test
    fun `the script carries the header reg import requires`() {
        assertTrue(script("md").startsWith("Windows Registry Editor Version 5.00"))
    }

    @Test
    fun `the open command is the exe followed by the file argument`() {
        val text = script("md")
        // This is the value the previous `reg add` form could not write reliably:
        // both the exe and %1 quoted, inside an escaped .reg string.
        assertTrue(
            text.contains("""@="\"C:\\Program Files\\BOSS\\BOSS.exe\" \"%1\""""),
            "the shell\\open\\command value is wrong:\n$text",
        )
    }

    @Test
    fun `each extension gets its own ProgID with all five entries`() {
        val text = script("md")
        listOf(
            """[HKEY_CURRENT_USER\Software\Classes\BOSS.md]""",
            """[HKEY_CURRENT_USER\Software\Classes\BOSS.md\DefaultIcon]""",
            """[HKEY_CURRENT_USER\Software\Classes\BOSS.md\shell\open\command]""",
            """[HKEY_CURRENT_USER\Software\Classes\.md\OpenWithProgids]""",
            """"BOSS.md"=hex(0):""",
            """".md"="BOSS.md"""",
        ).forEach { fragment ->
            assertTrue(text.contains(fragment), "missing from the script: $fragment")
        }
    }

    @Test
    fun `extensions are lowercased and de-duplicated`() {
        val text = script("MD", "md", "Kt")
        assertEquals(1, Regex("""\\BOSS\.md]""").findAll(text).count())
        assertTrue(text.contains("""\BOSS.kt]"""))
        assertFalse(text.contains("BOSS.MD"))
    }

    @Test
    fun `the ProgID is per extension, not per category`() {
        // FileAssociations and UserChoice are both keyed by extension, so a shared
        // ProgID would make "BOSS opens markdown" and "BOSS opens Kotlin" one
        // switch.
        assertEquals("BOSS.md", WindowsRegistryScript.progIdFor("md"))
        assertEquals("BOSS.kt", WindowsRegistryScript.progIdFor("KT"))
    }

    @Test
    fun `the description names the category and BOSS`() {
        assertTrue(script("md").contains("""@="Markdown (BOSS)""""))
    }

    @Test
    fun `every extension in a real category ends up in the script`() {
        val extensions = FileTypeCategories.table.extensionsFor("source-code")
        assertTrue(extensions.size > 50, "expected the big category, got ${extensions.size}")
        val text = WindowsRegistryScript.buildScript(extensions, exe, "Source code and config")
        extensions.forEach { extension ->
            assertTrue(
                text.contains("""\BOSS.${extension.lowercase()}]"""),
                ".$extension has no ProgID in the script",
            )
        }
    }

    // ---- the query parser ----

    /** `reg query ... /s` output, in the shape reg.exe actually prints. */
    private val regOutput =
        """

        HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\.md
            Progid    REG_SZ    Applications\notepad.exe

        HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\.md\OpenWithList
            a    REG_SZ    notepad.exe

        HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\.md\UserChoice
            Hash    REG_SZ    abcd1234=
            ProgId    REG_SZ    BOSS.md

        HKEY_CURRENT_USER\Software\Microsoft\Windows\CurrentVersion\Explorer\FileExts\.kt\UserChoice
            ProgId    REG_SZ    IntelliJ.kt

        """.trimIndent()

    @Test
    fun `the parser reads UserChoice ProgIds and nothing else`() {
        val choices = WindowsRegistryScript.parseUserChoices(regOutput)
        assertEquals("BOSS.md", choices["md"])
        assertEquals("IntelliJ.kt", choices["kt"])
        // The `Progid` under the extension key itself is NOT the user's choice -
        // reading it would report an association the shell does not honour.
        assertEquals(2, choices.size, "unexpected extra entries: $choices")
    }

    @Test
    fun `tab-separated output parses too`() {
        val tabbed =
            "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\FileExts\\.sh\\UserChoice\n" +
                "\tProgId\tREG_SZ\tBOSS.sh\n"
        assertEquals("BOSS.sh", WindowsRegistryScript.parseUserChoices(tabbed)["sh"])
    }

    @Test
    fun `empty and unparseable output yields nothing rather than throwing`() {
        assertTrue(WindowsRegistryScript.parseUserChoices("").isEmpty())
        assertTrue(WindowsRegistryScript.parseUserChoices("ERROR: The system was unable to find").isEmpty())
        assertTrue(WindowsRegistryScript.parseUserChoices("ProgId    REG_SZ    Orphan.md").isEmpty())
    }

    @Test
    fun `the userChoice key names the extension with its dot`() {
        assertTrue(WindowsRegistryScript.userChoiceKey("MD").endsWith("""FileExts\.md\UserChoice"""))
    }
}
