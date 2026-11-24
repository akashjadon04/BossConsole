import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for the `.deb` control-file rewrite applied by the `fixLinuxDesktopFile` task.
 *
 * The `dpkg-deb` round trip only happens on a Linux runner, so this is where the string
 * transform itself is verified — including the folded-field cases that are easy to get
 * wrong and impossible to notice until a Linux release build fails.
 */
class DebControlTest {
    private fun soften(control: String) = DebControl.softenXdgUtilsDependency(control)

    @Test
    fun `moves xdg-utils out of a jpackage-shaped control file`() {
        val control =
            """
            Package: boss
            Version: 9.2.60-1
            Section: utility
            Maintainer: support@risalabs.ai
            Priority: optional
            Architecture: arm64
            Provides: boss
            Depends: xdg-utils, libc6 (>= 2.17), libgcc-s1
            Description: BOSS
             Business Operating System Service
            """.trimIndent() + "\n"

        assertEquals(
            """
            Package: boss
            Version: 9.2.60-1
            Section: utility
            Maintainer: support@risalabs.ai
            Priority: optional
            Architecture: arm64
            Provides: boss
            Depends: libc6 (>= 2.17), libgcc-s1
            Recommends: xdg-utils
            Description: BOSS
             Business Operating System Service
            """.trimIndent() + "\n",
            soften(control),
        )
    }

    @Test
    fun `drops the Depends field when xdg-utils was its only entry`() {
        assertEquals(
            "Package: boss\nRecommends: xdg-utils\nDescription: BOSS\n",
            soften("Package: boss\nDepends: xdg-utils\nDescription: BOSS\n"),
        )
    }

    @Test
    fun `extends an existing Recommends field instead of replacing it`() {
        assertEquals(
            "Package: boss\nDepends: libc6\nRecommends: libnotify4, xdg-utils\nDescription: BOSS\n",
            soften("Package: boss\nDepends: xdg-utils, libc6\nRecommends: libnotify4\nDescription: BOSS\n"),
        )
    }

    @Test
    fun `handles a folded Depends field`() {
        assertEquals(
            "Package: boss\nDepends: libc6 (>= 2.17), libgcc-s1\nRecommends: xdg-utils\nDescription: BOSS\n",
            soften("Package: boss\nDepends: xdg-utils,\n libc6 (>= 2.17),\n libgcc-s1\nDescription: BOSS\n"),
        )
    }

    /**
     * Regression: the field name must be stripped from the first line only. Debian
     * dependency values legitimately contain colons — epoch versions — and treating
     * every continuation line as `name: value` silently dropped the dependency in
     * front of the colon and left an invalid `4.4)` entry behind.
     */
    @Test
    fun `keeps epoch versions on continuation lines intact`() {
        assertEquals(
            "Package: boss\nDepends: libasound2, libavcodec58 (>= 7:4.4)\nRecommends: xdg-utils\nDescription: BOSS\n",
            soften("Package: boss\nDepends: libasound2,\n libavcodec58 (>= 7:4.4), xdg-utils\nDescription: BOSS\n"),
        )
    }

    @Test
    fun `keeps arch qualifiers on continuation lines intact`() {
        assertEquals(
            "Package: boss\nDepends: libc6:amd64, zlib1g (>= 1:1.1.4)\nRecommends: xdg-utils\nDescription: BOSS\n",
            soften("Package: boss\nDepends: libc6:amd64,\n zlib1g (>= 1:1.1.4),\n xdg-utils\nDescription: BOSS\n"),
        )
    }

    @Test
    fun `reads a folded Recommends field without corrupting it`() {
        assertEquals(
            "Package: boss\nDepends: libc6\nRecommends: libnotify4 (>= 1:0.7), libglib2.0-0, xdg-utils\nDescription: BOSS\n",
            soften("Package: boss\nDepends: xdg-utils, libc6\nRecommends: libnotify4 (>= 1:0.7),\n libglib2.0-0\nDescription: BOSS\n"),
        )
    }

    @Test
    fun `recognizes a versioned xdg-utils entry`() {
        assertEquals(
            "Package: boss\nDepends: libc6\nRecommends: xdg-utils\nDescription: BOSS\n",
            soften("Package: boss\nDepends: libc6, xdg-utils (>= 1.1.3)\nDescription: BOSS\n"),
        )
    }

    @Test
    fun `recognizes an arch-qualified xdg-utils entry`() {
        assertEquals(
            "Package: boss\nDepends: libc6\nRecommends: xdg-utils\nDescription: BOSS\n",
            soften("Package: boss\nDepends: libc6, xdg-utils:amd64\nDescription: BOSS\n"),
        )
    }

    @Test
    fun `leaves alternatives that offer a non-xdg-utils option alone`() {
        assertNull(soften("Package: boss\nDepends: xdg-utils | libgtk-3-0, libc6\nDescription: BOSS\n"))
    }

    @Test
    fun `returns null when there is no xdg-utils dependency`() {
        assertNull(soften("Package: boss\nDepends: libc6\nDescription: BOSS\n"))
    }

    @Test
    fun `returns null when there is no Depends field at all`() {
        assertNull(soften("Package: boss\nDescription: BOSS\n"))
    }

    @Test
    fun `is idempotent - a second pass finds nothing to do`() {
        val once = soften("Package: boss\nDepends: xdg-utils, libc6\nDescription: BOSS\n")
        assertNull(once?.let { soften(it) })
    }

    @Test
    fun `does not add xdg-utils to Recommends twice`() {
        assertEquals(
            "Package: boss\nDepends: libc6\nRecommends: xdg-utils\nDescription: BOSS\n",
            soften("Package: boss\nDepends: xdg-utils, libc6\nRecommends: xdg-utils\nDescription: BOSS\n"),
        )
    }

    /** Boundary: `Depends` is the last field, so `Recommends` is appended at the end. */
    @Test
    fun `appends Recommends when Depends is the final field`() {
        assertEquals(
            "Package: boss\nDescription: BOSS\nDepends: libc6\nRecommends: xdg-utils\n",
            soften("Package: boss\nDescription: BOSS\nDepends: xdg-utils, libc6\n"),
        )
    }

    /** Same boundary with the field dropped entirely rather than rewritten. */
    @Test
    fun `appends Recommends when a dropped Depends was the final field`() {
        assertEquals(
            "Package: boss\nDescription: BOSS\nRecommends: xdg-utils\n",
            soften("Package: boss\nDescription: BOSS\nDepends: xdg-utils\n"),
        )
    }

    /**
     * The realistic jpackage shape: a folded `Description` is the final field. The appended
     * `Recommends` lands immediately after `Depends` — i.e. *before* the whole folded
     * field — because a column-0 line terminates the fold, so the continuation lines stay
     * attached to `Description`. This is the case that would break if field grouping ever
     * mis-attributed continuation lines.
     */
    @Test
    fun `appends Recommends after a folded final Description`() {
        assertEquals(
            "Package: boss\nDepends: libc6\nRecommends: xdg-utils\nDescription: BOSS\n" +
                " Business Operating System Service\n .\n Second paragraph\n",
            soften(
                "Package: boss\nDepends: xdg-utils, libc6\nDescription: BOSS\n" +
                    " Business Operating System Service\n .\n Second paragraph\n",
            ),
        )
    }

    @Test
    fun `leaves every other field byte-for-byte alone`() {
        val rewritten =
            soften(
                "Package: boss\nDepends: xdg-utils, libc6\nDescription: BOSS\n" +
                    " A long description\n .\n with a continuation and a colon: here\n",
            )
        assertEquals(
            "Package: boss\nDepends: libc6\nRecommends: xdg-utils\nDescription: BOSS\n" +
                " A long description\n .\n with a continuation and a colon: here\n",
            rewritten,
        )
    }
}
