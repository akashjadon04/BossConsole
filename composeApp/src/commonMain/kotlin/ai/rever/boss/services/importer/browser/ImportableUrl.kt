package ai.rever.boss.services.importer.browser

/** Schemes worth importing as a browsable bookmark. */
private val IMPORTABLE_SCHEMES = listOf("http://", "https://", "ftp://", "file://")

/**
 * True when [url] names a page a bookmark could actually open.
 *
 * Shared by every reader: the predicate was duplicated in three of them and
 * missing from Safari, so `javascript:` bookmarklets imported from one source
 * and not the others.
 */
internal fun isImportableUrl(url: String?): Boolean {
    val lower = url.orEmpty().trim().lowercase()
    return IMPORTABLE_SCHEMES.any { lower.startsWith(it) }
}
