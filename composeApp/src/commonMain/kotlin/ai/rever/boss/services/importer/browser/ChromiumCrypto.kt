package ai.rever.boss.services.importer.browser

import ai.rever.boss.utils.logging.BossLogger
import ai.rever.boss.utils.logging.LogCategory
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts the `password_value` blobs Chromium stores in `Login Data`.
 *
 * Chromium does not store passwords in the clear, but it also does not protect
 * them from the logged-in user — the key is held by the OS keychain and handed
 * back on request. Each platform does this differently:
 *
 * - **macOS** — a random key is kept in the login keychain as the generic
 *   password "Chrome Safe Storage". Values are `v10` + AES-128-CBC, key derived
 *   PBKDF2-HMAC-SHA1(password, salt="saltysalt", 1003 iterations, 16 bytes),
 *   IV = sixteen spaces.
 * - **Windows** — `Local State` holds an `encrypted_key`, itself DPAPI-wrapped;
 *   values are `v10` + AES-256-GCM. Unwrapping needs DPAPI, which the JDK has no
 *   binding for, so this is not supported here.
 * - **Linux** — key comes from the desktop keyring, or the fixed password
 *   "peanuts" when no keyring is present.
 *
 * Reading the macOS keychain entry prompts the user for permission the first
 * time. That prompt is the intended consent step: it is the OS asking whether
 * BOSS may read Chrome's passwords.
 */
internal object ChromiumCrypto {
    private val logger = BossLogger.forComponent("ChromiumCrypto")

    private const val SALT = "saltysalt"
    private const val ITERATIONS_MAC = 1003
    private const val ITERATIONS_LINUX = 1
    private const val KEY_LENGTH_BITS = 128

    /** Chromium uses a fixed all-spaces IV for the v10 CBC scheme. */
    private const val IV_LENGTH = 16
    private const val SECURITY_TIMEOUT_SECONDS = 60L

    /** Chromium's marker for "encrypted with the v10 scheme". */
    private val V10 = "v10".toByteArray(Charsets.US_ASCII)
    private val V11 = "v11".toByteArray(Charsets.US_ASCII)

    /** Why a profile's passwords cannot be decrypted on this machine. */
    class UnsupportedPlatformException(
        message: String,
    ) : Exception(message)

    /** Raised when the user declines the keychain prompt, or it is unavailable. */
    class KeyUnavailableException(
        message: String,
    ) : Exception(message)

    /**
     * Derive the AES key for [browserName]'s store.
     *
     * @param browserName used to pick the right keychain entry ("Chrome Safe
     *   Storage", "Brave Safe Storage", …)
     */
    fun deriveKey(browserName: String): SecretKeySpec =
        when {
            BrowserDetector.isMac -> {
                macKey(browserName)
            }

            BrowserDetector.isWindows -> {
                throw UnsupportedPlatformException(
                    "Importing saved passwords from Chromium browsers isn't supported on Windows yet - " +
                        "the key is sealed with DPAPI. Export a CSV from the browser instead.",
                )
            }

            else -> {
                linuxKey()
            }
        }

    /**
     * Keychain service name per browser.
     *
     * Not derivable from the display name: Chrome's entry is "Chrome Safe
     * Storage", never "Google Chrome Safe Storage". Verified against a real
     * keychain — guessing here silently yields "no saved passwords".
     */
    private val KEYCHAIN_SERVICES =
        mapOf(
            "Google Chrome" to "Chrome Safe Storage",
            "Google Chrome Beta" to "Chrome Beta Safe Storage",
            "Google Chrome Canary" to "Chrome Canary Safe Storage",
            "Microsoft Edge" to "Microsoft Edge Safe Storage",
            "Brave" to "Brave Safe Storage",
            "Vivaldi" to "Vivaldi Safe Storage",
            "Opera" to "Opera Safe Storage",
            "Arc" to "Arc Safe Storage",
            "Chromium" to "Chromium Safe Storage",
        )

    /** Read the per-browser secret out of the macOS login keychain. */
    private fun macKey(browserName: String): SecretKeySpec {
        val service = KEYCHAIN_SERVICES[browserName] ?: "$browserName Safe Storage"
        val result =
            runProcess(
                listOf("/usr/bin/security", "find-generic-password", "-w", "-s", service),
                SECURITY_TIMEOUT_SECONDS,
            )
        val secret = result.stdout.trim()

        if (result.exitCode == TIMED_OUT_EXIT_CODE) {
            throw KeyUnavailableException("The keychain prompt timed out.")
        }

        if (result.exitCode != 0 || secret.isEmpty()) {
            logger.warn(
                LogCategory.AUTH,
                "Could not read the browser's keychain key",
                mapOf("service" to service, "exit" to result.exitCode),
            )
            throw KeyUnavailableException(
                if (result.stderr.contains("User canceled", ignoreCase = true)) {
                    "Keychain access was denied, so $browserName's passwords can't be read."
                } else {
                    "$browserName's encryption key isn't in the keychain - it may never have saved a password."
                },
            )
        }
        return pbkdf2(secret, ITERATIONS_MAC)
    }

    /**
     * Linux: without a keyring binding, only the documented fallback password
     * works. Profiles sealed by a real keyring will fail to decrypt, which the
     * caller reports per entry rather than aborting.
     */
    private fun linuxKey(): SecretKeySpec = pbkdf2("peanuts", ITERATIONS_LINUX)

    private fun pbkdf2(
        password: String,
        iterations: Int,
    ): SecretKeySpec {
        val spec = PBEKeySpec(password.toCharArray(), SALT.toByteArray(Charsets.UTF_8), iterations, KEY_LENGTH_BITS)
        val key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec)
        // Clear the char[] the spec copied; the derived key is what we keep.
        spec.clearPassword()
        return SecretKeySpec(key.encoded, "AES")
    }

    /**
     * Decrypt one `password_value`, or null when the blob cannot be read.
     *
     * Returns null rather than throwing so one unreadable row does not abandon
     * the whole import.
     */
    fun decrypt(
        blob: ByteArray,
        key: SecretKeySpec,
    ): String? {
        if (blob.isEmpty()) return null

        return try {
            when {
                blob.startsWithPrefix(V10) || blob.startsWithPrefix(V11) -> {
                    // PKCS5Padding, not NoPadding: with NoPadding nothing in this
                    // path can fail, so a wrong key silently yields garbage that
                    // gets stored as the user's password. Padding validation
                    // rejects a wrong key ~255/256 of the time.
                    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                    val iv = IvParameterSpec(ByteArray(IV_LENGTH) { ' '.code.toByte() })
                    cipher.init(Cipher.DECRYPT_MODE, key, iv)
                    decodeStrictUtf8(cipher.doFinal(blob.copyOfRange(V10.size, blob.size)))
                }

                else -> {
                    // Older profiles stored the value unencrypted.
                    decodeStrictUtf8(blob)
                }
            }
        } catch (e: GeneralSecurityException) {
            logger.debug(
                LogCategory.AUTH,
                "Skipping an undecryptable password entry",
                mapOf("reason" to (e::class.simpleName ?: "unknown")),
            )
            null
        } catch (e: CharacterCodingException) {
            // Padding can pass by chance on a wrong key; a plausible-looking
            // password that isn't valid UTF-8 is still garbage. Rejecting it
            // beats writing it into the vault.
            logger.debug(
                LogCategory.AUTH,
                "Skipping a password entry that did not decode as text",
                mapOf("reason" to (e::class.simpleName ?: "unknown")),
            )
            null
        }
    }

    /** Decode as UTF-8, throwing rather than substituting U+FFFD. */
    private fun decodeStrictUtf8(bytes: ByteArray): String =
        Charsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()

    private fun ByteArray.startsWithPrefix(prefix: ByteArray): Boolean {
        if (size < prefix.size) return false
        return prefix.indices.all { this[it] == prefix[it] }
    }
}
