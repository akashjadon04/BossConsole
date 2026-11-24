package ai.rever.boss.components.auth

import ai.rever.boss.components.auth.forms.AUTH_BRAND_ASSETS
import ai.rever.boss.components.auth.forms.AUTH_BRAND_PAGE
import boss_kotlin.composeapp.generated.resources.Res
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the properties the vendored brand page claims about itself.
 *
 * Every one of these is asserted somewhere in a KDoc or an HTML comment today, and every one of them fails
 * SILENTLY: an unresolvable resource leaves an unstyled or static panel, a surviving `@import` turns an
 * offline page into a network fetch on the sign-in path, and a stray link turns a decorative panel into
 * something a user can navigate away from with no way back. None of that shows up in a diff or on the
 * machine of whoever changes it.
 *
 * It is also not hypothetical. Stripping the Google Fonts `@import` was done with `@import"[^;]*;`, and the
 * font URL contains semicolons (`wght@400;500;600`) - so the match ended inside the string and left broken
 * CSS at the top of the file. That was caught by eye. This is the test that catches it mechanically.
 */
class AuthBrandAssetsTest {
    @Test
    fun `the page and every asset it links are packaged`() =
        runTest {
            // Res.readBytes throws MissingResourceException rather than returning null, so reaching the
            // assertion at all is most of the check.
            val page = Res.readBytes(AUTH_BRAND_PAGE)
            assertTrue(page.isNotEmpty(), "$AUTH_BRAND_PAGE is packaged but empty")

            for (asset in AUTH_BRAND_ASSETS) {
                val bytes = Res.readBytes(asset)
                assertTrue(bytes.isNotEmpty(), "$asset is packaged but empty")
            }
        }

    @Test
    fun `the page links exactly the assets that are shipped beside it`() =
        runTest {
            // The page references them relatively, so the FILE NAMES are the contract - the loader writes
            // each asset into the temp directory under its own name. A rename on either side breaks the
            // page quietly: a missing stylesheet renders unstyled, a missing script renders static.
            val html = Res.readBytes(AUTH_BRAND_PAGE).decodeToString()
            for (asset in AUTH_BRAND_ASSETS) {
                val fileName = asset.substringAfterLast('/')
                assertTrue(
                    fileName in html,
                    "$fileName is shipped but nothing in index.html references it",
                )
            }
        }

    @Test
    fun `nothing in the bundle reaches the network`() =
        runTest {
            val css = Res.readBytes(AUTH_BRAND_ASSETS.first { it.endsWith(".css") }).decodeToString()
            val html = Res.readBytes(AUTH_BRAND_PAGE).decodeToString()

            assertTrue(
                "@import" !in css,
                "site.css has an @import - an offline page must not fetch anything, and the Google Fonts " +
                    "import is the one this had",
            )
            // Comments may name a URL (Tailwind's licence line does), so this looks for a FETCH: a url()
            // pointing at a scheme, or a src/href attribute doing the same.
            assertTrue(
                Regex("""url\(\s*["']?https?:""").containsMatchIn(css).not(),
                "site.css fetches a remote resource through url()",
            )
            assertTrue(
                Regex("""(?:src|href)\s*=\s*["']https?:""").containsMatchIn(html).not(),
                "index.html fetches a remote resource",
            )
        }

    @Test
    fun `the page has nothing a user can navigate away with`() =
        runTest {
            val html = Res.readBytes(AUTH_BRAND_PAGE).decodeToString()
            // The panel is a native browser surface with no chrome, so a link is a one-way trip: there is
            // no back button and no way to return short of relaunching. The site's own hero action buttons
            // were removed for this reason, and this keeps them out.
            assertTrue("<a " !in html && "<a\n" !in html, "index.html has an anchor; a link here is a trap")
            // A class ATTRIBUTE, not the bare string: the file's own comment explains that `.hero-actions`
            // was removed and why, and a substring check flagged that comment as the violation.
            assertTrue(
                Regex("""class\s*=\s*["'][^"']*hero-actions""").containsMatchIn(html).not(),
                "the site's hero action buttons are back in index.html; they lead nowhere from a login panel",
            )
        }
}
