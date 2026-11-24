package ai.rever.boss.updater

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/**
 * Fail-closed tests for [UpdateInstaller.validateDownloadFile] (Issue #37).
 *
 * Previously a canonical path escaping the staging directory, or a filename
 * carrying a shell metacharacter, only logged a warning and the update installed
 * anyway — while [UpdateScriptGenerator]'s validation rejected the identical
 * input. Both entry points now share [UpdatePathValidator] and both refuse.
 *
 * The staging directory is injected, so these tests never touch (or leave litter
 * in) the real `$TMPDIR/boss-updates` that the running app uses.
 */
class UpdateDownloadValidationTest {
    @TempDir
    lateinit var tempDir: Path

    private val stagingDir: File
        get() = File(tempDir.toFile(), "boss-updates").also { it.mkdirs() }

    private fun stagedFile(name: String): File =
        File(stagingDir, name).also {
            it.writeText("not a real installer")
        }

    @Test
    fun `a clean artifact inside the staging directory is accepted`() {
        UpdateInstaller.validateDownloadFile(stagedFile("BOSS-9.9.9-Universal.dmg"), ".dmg", stagingDir)
    }

    @Test
    fun `an artifact outside the staging directory is rejected`() {
        val outside = File(tempDir.toFile(), "elsewhere").also { it.mkdirs() }
        val file = File(outside, "BOSS-9.9.9-Universal.dmg").also { it.writeText("not a real installer") }

        val exception =
            assertThrows<SecurityException> {
                UpdateInstaller.validateDownloadFile(file, ".dmg", stagingDir)
            }
        assertTrue(
            exception.message?.contains("outside the staging directory") == true,
            "Escaping the staging directory must fail closed, got: ${exception.message}",
        )
    }

    /**
     * A sibling directory whose name merely starts with the staging directory's
     * name must not satisfy the containment check.
     */
    @Test
    fun `a sibling directory sharing the staging prefix is rejected`() {
        val sibling = File(tempDir.toFile(), "boss-updates-evil").also { it.mkdirs() }
        val file = File(sibling, "BOSS-9.9.9-Universal.dmg").also { it.writeText("not a real installer") }

        assertThrows<SecurityException> {
            UpdateInstaller.validateDownloadFile(file, ".dmg", stagingDir)
        }
    }

    /**
     * A symlink *inside* staging that points outside it must be rejected.
     *
     * This one caught a real Windows-only hole: containment used to compare
     * `File.getCanonicalPath()`, and on Windows that does not resolve reparse
     * points (JDK 17's `canonicalize_md.c` has no reparse handling at all), so the
     * link kept a canonical path inside staging and the escape was accepted. It now
     * compares `Path.toRealPath()`, which is specified to resolve links on every
     * platform. macOS/Linux always passed; only the Windows CI leg failed.
     */
    @Test
    fun `a symlink inside staging pointing outside it is rejected`() {
        val outside = File(tempDir.toFile(), "outside").also { it.mkdirs() }
        val realFile = File(outside, "payload.dmg").also { it.writeText("not a real installer") }
        val link = File(stagingDir, "BOSS-9.9.9-Universal.dmg")
        try {
            Files.createSymbolicLink(link.toPath(), realFile.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "Filesystem does not support symlinks: ${e.message}")
        } catch (e: java.io.IOException) {
            assumeTrue(false, "Could not create a symlink (Windows needs privileges): ${e.message}")
        }

        val exception =
            assertThrows<SecurityException> {
                UpdateInstaller.validateDownloadFile(link, ".dmg", stagingDir)
            }
        assertTrue(
            exception.message?.contains("outside the staging directory") == true,
            "A symlink out of staging must fail closed, got: ${exception.message}",
        )
    }

    /**
     * Both sides are resolved, not just the artifact: where the staging directory
     * itself is reached through a symlink - macOS `$TMPDIR` lives under
     * `/var`, which is a link to `/private/var` - a legitimate download inside it
     * must still be accepted. Resolving only the file side would reject every
     * update on those systems.
     */
    @Test
    fun `a legitimate artifact is accepted when the staging directory itself is a symlink`() {
        val realStaging = File(tempDir.toFile(), "real-staging").also { it.mkdirs() }
        val linkedStaging = File(tempDir.toFile(), "linked-staging")
        try {
            Files.createSymbolicLink(linkedStaging.toPath(), realStaging.toPath())
        } catch (e: UnsupportedOperationException) {
            assumeTrue(false, "Filesystem does not support symlinks: ${e.message}")
        } catch (e: java.io.IOException) {
            assumeTrue(false, "Could not create a symlink (Windows needs privileges): ${e.message}")
        }
        val artifact = File(linkedStaging, "BOSS-9.9.9-Universal.dmg").also { it.writeText("installer") }

        // Staging passed in as the symlink, artifact addressed through it.
        UpdateInstaller.validateDownloadFile(artifact, ".dmg", linkedStaging)
        // And with staging passed in as the real directory.
        UpdateInstaller.validateDownloadFile(artifact, ".dmg", realStaging)
    }

    /**
     * Shell metacharacters in the artifact name are refused, and refused *before*
     * the filesystem is touched — the name is what gets interpolated into the
     * generated install script.
     */
    @Test
    fun `filenames carrying shell metacharacters are rejected`() {
        val hostileNames =
            listOf(
                "BOSS-9.9.9\$(whoami).dmg" to "shell metacharacters",
                "BOSS-9.9.9`whoami`.dmg" to "shell metacharacters",
                "BOSS-9.9.9;rm -rf ~.dmg" to "command separator",
                "BOSS-9.9.9|sh.dmg" to "command separator",
                "BOSS-9.9.9&sh.dmg" to "command separator",
                "BOSS-9.9.9%PATH%.dmg" to "Windows batch metacharacters",
                "BOSS-9.9.9^x.dmg" to "Windows batch metacharacters",
                "BOSS-9.9.9!x!.dmg" to "Windows batch metacharacters",
                "..BOSS-9.9.9.dmg" to "path traversal",
            )

        hostileNames.forEach { (name, expectedReason) ->
            // Deliberately a path that does NOT exist: validation must reject the
            // name without needing (or trusting) the filesystem.
            val file = File(stagingDir, name)
            val exception =
                assertThrows<SecurityException> {
                    UpdateInstaller.validateDownloadFile(file, ".dmg", stagingDir)
                }
            assertTrue(
                exception.message?.contains(expectedReason) == true,
                "Expected '$expectedReason' rejection for '$name', got: ${exception.message}",
            )
        }
    }

    @Test
    fun `filenames with newlines are rejected`() {
        val file = File(stagingDir, "BOSS-9.9.9.dmg\nrm -rf ~")
        val exception =
            assertThrows<SecurityException> {
                UpdateInstaller.validateDownloadFile(file, ".dmg", stagingDir)
            }
        assertTrue(
            exception.message?.contains("newline") == true,
            "Expected newline rejection, got: ${exception.message}",
        )
    }

    @Test
    fun `the wrong extension is rejected`() {
        val exception =
            assertThrows<SecurityException> {
                UpdateInstaller.validateDownloadFile(stagedFile("BOSS-9.9.9-Universal.zip"), ".dmg", stagingDir)
            }
        assertTrue(
            exception.message?.contains("Invalid file extension") == true,
            "Expected extension rejection, got: ${exception.message}",
        )
    }

    @Test
    fun `a missing artifact is rejected`() {
        val exception =
            assertThrows<SecurityException> {
                UpdateInstaller.validateDownloadFile(File(stagingDir, "BOSS-absent.dmg"), ".dmg", stagingDir)
            }
        assertTrue(
            exception.message?.contains("does not exist") == true,
            "Expected missing-file rejection, got: ${exception.message}",
        )
    }

    /**
     * Both entry points must agree: anything [UpdateScriptGenerator] refuses is
     * also refused by the installer's own validation.
     */
    @Test
    fun `installer validation agrees with the script generator on hostile names`() {
        val hostileName = "BOSS-9.9.9\$(whoami).dmg"

        val generatorRejection =
            assertThrows<SecurityException> {
                UpdateScriptGenerator.generateMacOSUpdateScript(
                    dmgPath = File(stagingDir, hostileName).absolutePath,
                    targetAppPath = "/Applications/BOSS.app",
                    appPid = 12345,
                )
            }
        val installerRejection =
            assertThrows<SecurityException> {
                UpdateInstaller.validateDownloadFile(File(stagingDir, hostileName), ".dmg", stagingDir)
            }

        assertTrue(generatorRejection.message?.contains("shell metacharacters") == true)
        assertTrue(installerRejection.message?.contains("shell metacharacters") == true)
    }

    // ==================== Asset name, at download time ====================

    /**
     * The release catalog supplies the asset name, and the download path used it as
     * a path component *before* any install-time check ran: a `..` escaped the
     * staging directory, and the `delete()` plus the write had already happened —
     * an arbitrary-file delete/overwrite primitive running as the user.
     */
    @Test
    fun `hostile asset names are rejected before they become a path`() {
        val hostileNames =
            listOf(
                "../../../../etc/passwd",
                "..",
                "sub/BOSS-9.9.9.dmg",
                "sub\\BOSS-9.9.9.dmg",
                "BOSS-9.9.9\$(whoami).dmg",
                "BOSS-9.9.9;rm -rf ~.dmg",
                "BOSS-9.9.9%PATH%.dmg",
            )

        hostileNames.forEach { name ->
            assertThrows<SecurityException>("Should reject asset name: $name") {
                validateUpdateAssetName(name)
            }
        }
    }

    /**
     * The degenerate case: the asset name defaults to "" upstream, and
     * `File(stagingDir, "")` resolves to the staging directory itself, so
     * `exists()` and `delete()` would target the directory.
     */
    @Test
    fun `an empty asset name is rejected`() {
        val exception = assertThrows<SecurityException> { validateUpdateAssetName("") }
        assertTrue(
            exception.message?.contains("empty") == true,
            "Expected an explicit empty-name rejection, got: ${exception.message}",
        )
    }

    @Test
    fun `legitimate asset names are accepted`() {
        listOf(
            "BOSS-9.2.60-Universal.dmg",
            "BOSS-9.2.60.msi",
            "BOSS-9.2.60-amd64.deb",
            "BOSS-9.2.60-arm64.rpm",
            "BOSS-9.2.60-amd64.jar",
        ).forEach { validateUpdateAssetName(it) }
    }
}
