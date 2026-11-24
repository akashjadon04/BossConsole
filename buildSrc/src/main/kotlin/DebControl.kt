/**
 * Pure transforms on a Debian `control` file.
 *
 * Lives in `buildSrc` rather than inline in `composeApp/build.gradle.kts` so it can be
 * unit-tested (see `DebControlTest`) without a Linux runner or a real
 * `dpkg-deb -R` / `--build` round trip. It is called from the `fixLinuxDesktopFile`
 * task, which post-processes the generated `.deb`.
 */
object DebControl {
    /** A field starts at column 0 with `Name:`; continuation lines start with space/tab. */
    private val FIELD_START = Regex("^([A-Za-z0-9][A-Za-z0-9-]*):(.*)$")

    /**
     * Leading package name of a dependency entry. Deliberately excludes `:` so an arch
     * qualifier (`xdg-utils:amd64`) still resolves to the package name.
     */
    private val PACKAGE_NAME = Regex("^[A-Za-z0-9][A-Za-z0-9+.-]*")

    private const val XDG_UTILS = "xdg-utils"

    private class Field(
        val name: String,
        val lines: MutableList<String>,
    )

    /**
     * Moves `xdg-utils` out of the `.deb`'s `Depends:` field and into `Recommends:`.
     *
     * jpackage puts `xdg-utils` in `Depends:` because its `postinst` calls
     * `xdg-desktop-menu` / `xdg-icon-resource`. On a headless image that makes
     * `dpkg -i` fail during dependency resolution — before `postinst` (and the
     * best-effort guard applied to it) ever runs. Desktop integration is optional, so
     * the dependency belongs under `Recommends:`, which apt still installs by default.
     *
     * Behavior:
     *  * `Depends:` is dropped entirely when `xdg-utils` was its only entry.
     *  * An existing `Recommends:` is extended rather than replaced.
     *  * An entry is only removed when *all* of its alternatives are `xdg-utils`, so
     *    `xdg-utils | something-else` is left alone.
     *  * Field folding is honored: the field name is stripped from the first line only,
     *    so colons inside the value (epoch versions like `(>= 7:4.4)`, arch qualifiers
     *    like `libc6:amd64`) survive on continuation lines. Rewritten fields are emitted
     *    unfolded; every other field is copied through verbatim.
     *  * Text before the first field (comments/blank lines — not produced by jpackage)
     *    is dropped, which is why callers should only feed it generated control files.
     *  * Line endings are normalized to `\n`: CRLF input would be rewritten wholesale,
     *    so "other fields are byte-for-byte" holds for LF input only. Unreachable via
     *    `dpkg-deb -R`, which writes LF.
     *
     * @return the rewritten control text, or null when there is nothing to change
     *   (which makes the caller idempotent across repacks).
     */
    fun softenXdgUtilsDependency(controlText: String): String? {
        val fields = parseFields(controlText)
        val dependsIndex = fields.indexOfFirst { it.name.equals("Depends", ignoreCase = true) }
        val entries =
            if (dependsIndex < 0) {
                emptyList()
            } else {
                splitEntries(fieldValue(fields[dependsIndex].lines))
            }
        if (entries.none(::isXdgUtils)) return null

        val kept = entries.filterNot(::isXdgUtils)
        if (kept.isEmpty()) {
            fields.removeAt(dependsIndex)
        } else {
            val name = fields[dependsIndex].name
            fields[dependsIndex] = Field(name, mutableListOf("$name: ${kept.joinToString(", ")}"))
        }

        val recommendsIndex = fields.indexOfFirst { it.name.equals("Recommends", ignoreCase = true) }
        if (recommendsIndex < 0) {
            // Put it where Depends was, so the field order still reads naturally.
            val insertAt = if (kept.isEmpty()) dependsIndex else dependsIndex + 1
            fields.add(insertAt, Field("Recommends", mutableListOf("Recommends: $XDG_UTILS")))
        } else {
            val name = fields[recommendsIndex].name
            val existing = splitEntries(fieldValue(fields[recommendsIndex].lines))
            if (existing.none(::isXdgUtils)) {
                fields[recommendsIndex] = Field(name, mutableListOf("$name: ${(existing + XDG_UTILS).joinToString(", ")}"))
            }
        }

        return fields.flatMap { it.lines }.joinToString("\n").trimEnd('\n') + "\n"
    }

    /** Groups the file into fields, keeping each field's original lines. */
    private fun parseFields(controlText: String): MutableList<Field> {
        val fields = mutableListOf<Field>()
        controlText.lines().forEach { line ->
            val match = FIELD_START.find(line)
            when {
                match != null -> fields += Field(match.groupValues[1], mutableListOf(line))
                fields.isNotEmpty() -> fields.last().lines += line
                else -> Unit // Preamble before the first field; jpackage does not emit any.
            }
        }
        return fields
    }

    /**
     * Value of a (possibly folded) field. Only the first line carries the `Name:`
     * prefix — stripping a colon from continuation lines would eat epoch versions and
     * arch qualifiers.
     */
    private fun fieldValue(lines: List<String>): String =
        lines
            .mapIndexed { index, line -> if (index == 0) line.substringAfter(':') else line }
            .joinToString(" ") { it.trim() }
            .trim()

    private fun splitEntries(value: String): List<String> = value.split(",").map { it.trim() }.filter { it.isNotEmpty() }

    /** True when every alternative of a dependency entry names xdg-utils. */
    private fun isXdgUtils(entry: String): Boolean =
        entry.split("|").all { alternative ->
            PACKAGE_NAME.find(alternative.trim())?.value == XDG_UTILS
        }
}
