package ai.rever.boss.services.importer.browser

import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Covers [ChromiumCrypto.decrypt].
 *
 * The case that matters is the wrong key. With `AES/CBC/NoPadding` and a
 * lenient UTF-8 decode, nothing in that path could fail: a wrong key produced
 * plausible-looking garbage, the reader counted it as a usable credential, and
 * it was written into the vault as that site's password. Every assertion below
 * that expects `null` would have returned a string instead.
 */
class ChromiumCryptoTest {
    private fun key(seed: Byte) = SecretKeySpec(ByteArray(16) { seed }, "AES")

    /** Encrypt the way Chromium does: "v10" + AES-128-CBC, all-spaces IV. */
    private fun sealV10(
        plaintext: String,
        key: SecretKeySpec,
    ): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(ByteArray(16) { ' '.code.toByte() }))
        return "v10".toByteArray(Charsets.US_ASCII) + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    @Test
    fun `round-trips a password sealed with the same key`() {
        val k = key(1)

        val secret = "correct horse battery staple"

        assertEquals(secret, ChromiumCrypto.decrypt(sealV10(secret, k), k))
    }

    @Test
    fun `a wrong key yields null rather than garbage`() {
        val sealed = sealV10("hunter2", key(1))

        // Padding validation rejects a wrong key ~255/256 of the time, and the
        // strict UTF-8 decode catches most of the remainder. Either way the
        // answer must not be a string that gets stored as the user's password.
        assertNull(ChromiumCrypto.decrypt(sealed, key(2)))
    }

    @Test
    fun `no key in the world decrypts an empty blob`() {
        assertNull(ChromiumCrypto.decrypt(ByteArray(0), key(1)))
    }

    @Test
    fun `a legacy unencrypted value is returned as-is`() {
        // Old profiles stored the value in the clear, with no version prefix.
        val plain = "legacy-password".toByteArray(Charsets.UTF_8)

        assertEquals("legacy-password", ChromiumCrypto.decrypt(plain, key(1)))
    }

    @Test
    fun `bytes that are not valid text are rejected`() {
        // A lone 0x80 continuation byte is not valid UTF-8. Lenient decoding
        // would substitute U+FFFD and hand back a "password" of replacement
        // characters.
        val notText = byteArrayOf(0x80.toByte(), 0x81.toByte(), 0xFE.toByte())

        assertNull(ChromiumCrypto.decrypt(notText, key(1)))
    }

    @Test
    fun `a multi-block password survives the round trip`() {
        val k = key(7)
        val long = "x".repeat(200)

        assertEquals(long, ChromiumCrypto.decrypt(sealV10(long, k), k))
    }
}
