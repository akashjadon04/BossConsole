package ai.rever.boss.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for the content check that tells a repaired engine bundle from the one it
 * replaced.
 *
 * This is the only thing that can: the fix ships inside a *rebuild* of an
 * already-published engine, so `version.txt` reads `9.5.0` before and after and
 * `isChromiumInstalled`'s version equality short-circuits. Without a content
 * check, every existing install keeps the engine that declares http, https,
 * `file` and 17 document types under the name "BOSS" forever.
 */
class EngineDeclaresBrowserTypesTest {
    @TempDir
    lateinit var dir: File

    private fun plist(body: String): File {
        val f = File(dir, "Info.plist")
        f.writeText(
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
            $body
            </dict>
            </plist>
            """.trimIndent(),
        )
        return f
    }

    private fun check(file: File) = ChromiumAutoDownloader.declaresBrowserTypes(file)

    @Test
    fun `an engine claiming url schemes is stale`() {
        assertTrue(
            check(
                plist(
                    """
                    <key>CFBundleIdentifier</key><string>ai.rever.boss.browser</string>
                    <key>CFBundleURLTypes</key>
                    <array><dict><key>CFBundleURLSchemes</key><array><string>http</string></array></dict></array>
                    """.trimIndent(),
                ),
            ),
        )
    }

    @Test
    fun `an engine claiming document types is stale`() {
        assertTrue(
            check(
                plist(
                    """
                    <key>CFBundleDocumentTypes</key>
                    <array><dict><key>LSItemContentTypes</key><array><string>public.html</string></array></dict></array>
                    """.trimIndent(),
                ),
            ),
        )
    }

    @Test
    fun `a repaired engine is not stale`() {
        // What the fixed branding workflow produces: identity and the Bluetooth
        // keys survive, the two browser-type keys are gone.
        assertFalse(
            check(
                plist(
                    """
                    <key>CFBundleIdentifier</key><string>ai.rever.boss.browser</string>
                    <key>CFBundleName</key><string>BOSS</string>
                    <key>NSBluetoothAlwaysUsageDescription</key><string>passkeys</string>
                    <key>CFBundleShortVersionString</key><string>9.5.0</string>
                    """.trimIndent(),
                ),
            ),
        )
    }

    @Test
    fun `the key name outside a key element does not count`() {
        // Precision matters more than usual here: a false positive re-downloads
        // ~160 MB, and before the one-attempt guard it would have done so on
        // every launch. A comment or a string value naming the key is not a
        // declaration.
        assertFalse(
            check(
                plist(
                    """
                    <!-- CFBundleURLTypes was removed on purpose; see AGENTS.md -->
                    <key>BossNote</key><string>CFBundleDocumentTypes</string>
                    """.trimIndent(),
                ),
            ),
        )
    }

    @Test
    fun `a missing plist fails closed`() {
        // Answering true would force a large download over a file we could not
        // read. A genuinely broken bundle is caught by the executable checks.
        assertFalse(check(File(dir, "does-not-exist.plist")))
    }

    @Test
    fun `a directory in place of the plist fails closed`() {
        val asDir = File(dir, "Info.plist.d").apply { mkdirs() }
        assertFalse(check(asDir))
    }
}
