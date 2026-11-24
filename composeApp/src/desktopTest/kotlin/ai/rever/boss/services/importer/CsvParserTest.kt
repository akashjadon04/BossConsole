package ai.rever.boss.services.importer

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The CSV reader is hand-rolled, so these cover the RFC 4180 corners that real
 * exports actually contain rather than just the happy path.
 */
class CsvParserTest {
    @Test
    fun `splits a plain grid`() {
        val rows = CsvParser.parse("a,b,c\n1,2,3")

        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
    }

    @Test
    fun `keeps commas inside quoted fields`() {
        val rows = CsvParser.parse("""name,note${'\n'}"Acme, Inc",hello""")

        assertEquals(listOf("Acme, Inc", "hello"), rows[1])
    }

    @Test
    fun `unescapes doubled quotes`() {
        val rows = CsvParser.parse("""value${'\n'}"say ""hi"""""")

        assertEquals("""say "hi"""", rows[1].single())
    }

    @Test
    fun `keeps newlines inside quoted fields`() {
        val rows = CsvParser.parse("note\n\"line one\nline two\"")

        assertEquals(2, rows.size)
        assertEquals("line one\nline two", rows[1].single())
    }

    @Test
    fun `treats CRLF as one row terminator`() {
        val rows = CsvParser.parse("a,b\r\n1,2\r\n")

        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows)
    }

    @Test
    fun `ignores a UTF-8 BOM on the first header`() {
        val table = CsvParser.parseTable("\uFEFFurl,username,password\nhttps://x.test,u,p")

        // Without stripping the BOM the first header would not match the alias
        // table and the whole file would be rejected as "not a password export".
        assertEquals(0, CsvParser.urlColumn(table!!.header))
    }

    @Test
    fun `a trailing separator yields a final empty field`() {
        // "a,b," is three fields, the last empty. Dropping it made a row whose
        // last column is an empty password report MALFORMED_ROW rather than
        // MISSING_PASSWORD — the wrong reason shown to the user.
        assertEquals(listOf("a", "b", ""), CsvParser.parse("a,b,").single())
    }

    @Test
    fun `an empty trailing password is reported as missing, not malformed`() {
        val table = CsvParser.parseTable("url,username,password\nhttps://x.test,alice,")

        assertEquals(3, table!!.rows.single().size)
    }

    @Test
    fun `does not emit a phantom row for a trailing newline`() {
        val rows = CsvParser.parse("a,b\n1,2\n")

        assertEquals(2, rows.size)
    }

    @Test
    fun `maps Chrome headers`() {
        val header = listOf("name", "url", "username", "password", "note")

        assertEquals(1, CsvParser.urlColumn(header))
        assertEquals(2, CsvParser.usernameColumn(header))
        assertEquals(3, CsvParser.passwordColumn(header))
    }

    @Test
    fun `maps Safari headers regardless of case`() {
        val header = listOf("Title", "URL", "Username", "Password", "Notes", "OTPAuth")

        assertEquals(1, CsvParser.urlColumn(header))
        assertEquals(2, CsvParser.usernameColumn(header))
        assertEquals(3, CsvParser.passwordColumn(header))
        assertEquals(4, CsvParser.notesColumn(header))
    }

    @Test
    fun `maps Bitwarden headers`() {
        val header =
            listOf(
                "folder",
                "favorite",
                "type",
                "name",
                "notes",
                "fields",
                "login_uri",
                "login_username",
                "login_password",
                "login_totp",
            )

        assertEquals(6, CsvParser.urlColumn(header))
        assertEquals(7, CsvParser.usernameColumn(header))
        assertEquals(8, CsvParser.passwordColumn(header))
    }

    @Test
    fun `a bare quote mid-field is data, not a delimiter`() {
        // Toggling quote state anywhere in a field made a"b,c swallow the comma
        // and shift every later column.
        val rows = CsvParser.parse("a\"b,c")

        assertEquals(2, rows.single().size)
        assertEquals("a\"b", rows.single().first())
    }

    @Test
    fun `an edge-padded quoted value keeps its whitespace`() {
        // The password column is read untrimmed: " hunter2 " stored as
        // "hunter2" is an entry that looks right and does not work.
        val rows = CsvParser.parse("url,pw\nx," + '"' + " hunter2 " + '"')

        assertEquals(" hunter2 ", rows[1][1])
    }

    @Test
    fun `reports no password column when the file is not a password export`() {
        val header = listOf("first name", "last name", "company")

        assertTrue(CsvParser.passwordColumn(header) < 0)
    }
}
