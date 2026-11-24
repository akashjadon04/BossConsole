package ai.rever.boss.services.importer

/**
 * Minimal RFC 4180 CSV reader, plus the header mapping needed to read password
 * exports from the browsers and password managers people actually migrate from.
 *
 * Hand-rolled on purpose: the host has no CSV dependency and this needs about
 * a hundred lines. Handles quoted fields, doubled quotes, embedded commas and
 * newlines, CRLF, and a UTF-8 BOM.
 */
object CsvParser {
    /**
     * Column aliases, lowercased. Chrome/Firefox/1Password agree on
     * url/username/password; Safari capitalises; Bitwarden prefixes with login_.
     */
    private val URL_HEADERS = setOf("url", "login_uri", "uri", "website", "hostname", "web site")
    private val USERNAME_HEADERS = setOf("username", "login_username", "user", "login", "email", "user name")
    private val PASSWORD_HEADERS = setOf("password", "login_password", "pass")
    private val NOTES_HEADERS = setOf("notes", "note", "comment", "comments")

    /** A parsed grid: the header row plus the data rows. */
    data class Table(
        val header: List<String>,
        val rows: List<List<String>>,
    )

    /** What ended a field: another field follows, the row ended, or the input did. */
    private enum class FieldEnd { FIELD, ROW, INPUT }

    /** One field plus where the scan got to and why it stopped. */
    private data class Scan(
        val value: String,
        val next: Int,
        val end: FieldEnd,
    )

    /**
     * Split [text] into rows of fields.
     *
     * Reads one field at a time rather than branching per character, which keeps
     * the quoting rules in a single place: a field may be wrapped in double
     * quotes, a literal quote inside is written as two quotes, and commas or
     * newlines inside the quotes are data rather than separators.
     */
    fun parse(text: String): List<List<String>> {
        // A BOM would otherwise become part of the first header name and stop it
        // matching the alias table. Written as an escape on purpose: a literal
        // U+FEFF here is invisible and ktlintFormat silently strips it, which
        // would turn this into a no-op.
        val input = text.removePrefix("\uFEFF")

        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        var i = 0

        while (i < input.length) {
            val scan = readField(input, i)
            row.add(scan.value)
            i = scan.next

            // "a,b," ends on a separator, so there is a third — empty — field
            // after it that the loop would otherwise never read. Without this a
            // row whose last column is an empty password reports MALFORMED_ROW
            // instead of MISSING_PASSWORD.
            if (scan.end == FieldEnd.FIELD && i >= input.length) {
                row.add("")
            }

            if (scan.end != FieldEnd.FIELD) {
                // A trailing newline at end of file must not emit a phantom row.
                if (row.size > 1 || row.first().isNotEmpty()) rows.add(row)
                row = mutableListOf()
            }
        }
        if (row.isNotEmpty()) rows.add(row)

        return rows
    }

    /**
     * True when this quote opens or closes the field rather than being data.
     *
     * A quote only opens a field at its start; mid-field it is a literal.
     * Otherwise `a"b,c` swallows the comma and every later column shifts.
     */
    private fun isQuoteToggle(
        c: Char,
        inQuotes: Boolean,
        atFieldStart: Boolean,
    ): Boolean = c == '"' && (inQuotes || atFieldStart)

    /** True when position [i] is the `""` that RFC 4180 uses for a literal quote. */
    private fun isEscapedQuote(
        input: String,
        i: Int,
        inQuotes: Boolean,
    ): Boolean = inQuotes && input[i] == '"' && input.getOrNull(i + 1) == '"'

    /** Read one field starting at [start]. */
    private fun readField(
        input: String,
        start: Int,
    ): Scan {
        val field = StringBuilder()
        var i = start
        var inQuotes = false
        var end: FieldEnd? = null

        while (i < input.length && end == null) {
            val c = input[i]

            when {
                // Inside quotes, "" is an escaped quote rather than a close.
                isEscapedQuote(input, i, inQuotes) -> {
                    field.append('"')
                    i += 2
                }

                isQuoteToggle(c, inQuotes, atFieldStart = i == start) -> {
                    inQuotes = !inQuotes
                    i++
                }

                inQuotes -> {
                    field.append(c)
                    i++
                }

                c == ',' -> {
                    end = FieldEnd.FIELD
                    i++
                }

                c == '\n' || c == '\r' -> {
                    end = FieldEnd.ROW
                    // Consume CRLF as a single terminator.
                    i += if (c == '\r' && input.getOrNull(i + 1) == '\n') 2 else 1
                }

                else -> {
                    field.append(c)
                    i++
                }
            }
        }

        return Scan(field.toString(), i, end ?: FieldEnd.INPUT)
    }

    /** Parse into a header plus rows, or null when the text has no content. */
    fun parseTable(text: String): Table? {
        val rows = parse(text)
        if (rows.isEmpty()) return null
        return Table(header = rows.first().map { it.trim() }, rows = rows.drop(1))
    }

    /** Index of the first header matching [aliases], or -1. */
    fun columnIndex(
        header: List<String>,
        aliases: Set<String>,
    ): Int = header.indexOfFirst { it.trim().lowercase() in aliases }

    fun urlColumn(header: List<String>): Int = columnIndex(header, URL_HEADERS)

    fun usernameColumn(header: List<String>): Int = columnIndex(header, USERNAME_HEADERS)

    fun passwordColumn(header: List<String>): Int = columnIndex(header, PASSWORD_HEADERS)

    fun notesColumn(header: List<String>): Int = columnIndex(header, NOTES_HEADERS)
}
