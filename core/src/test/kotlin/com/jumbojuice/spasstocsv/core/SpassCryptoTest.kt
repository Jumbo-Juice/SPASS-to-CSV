package com.jumbojuice.spasstocsv.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets
import java.util.Base64

class SpassCryptoTest {

    @Test
    fun `decrypts a container produced with the documented layout`() {
        val payload = SpassTestFixtures.simplePasswordPayload()
        val file = SpassTestFixtures.encrypt(payload, "S3cret!")

        val decrypted = SpassCrypto.decrypt(file, "S3cret!".toCharArray())

        assertEquals(payload, String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun `container layout is salt then iv then ciphertext`() {
        val salt = ByteArray(20) { it.toByte() }
        val iv = ByteArray(16) { (it + 100).toByte() }
        val file = SpassTestFixtures.encrypt("25\ntrue\nnext_table\na\nYQ==\n", "pw", salt, iv)

        val raw = Base64.getMimeDecoder().decode(file)

        assertEquals(SpassTestFixtures.hex(salt), SpassTestFixtures.hex(raw.copyOfRange(0, 20)))
        assertEquals(SpassTestFixtures.hex(iv), SpassTestFixtures.hex(raw.copyOfRange(20, 36)))
        assertEquals(0, (raw.size - 36) % 16)
    }

    @Test
    fun `wrong password is reported as such and never as a crash`() {
        val file = SpassTestFixtures.encrypt(SpassTestFixtures.simplePasswordPayload(), "right")

        val error = assertThrows<SpassException.WrongPasswordOrCorrupt> {
            SpassCrypto.decrypt(file, "wrong".toCharArray())
        }
        assertTrue(error.message!!.contains("password", ignoreCase = true))
    }

    @Test
    fun `unicode passwords work`() {
        val payload = SpassTestFixtures.simplePasswordPayload()
        val password = "påsswörd-日本語-🔐"
        val file = SpassTestFixtures.encrypt(payload, password)

        assertEquals(payload, String(SpassCrypto.decrypt(file, password.toCharArray()), StandardCharsets.UTF_8))
    }

    @Test
    fun `base64 wrapped across lines still decodes`() {
        val payload = SpassTestFixtures.simplePasswordPayload()
        val flat = String(SpassTestFixtures.encrypt(payload, "pw"), StandardCharsets.US_ASCII)
        val wrapped = flat.chunked(76).joinToString("\r\n")

        val decrypted = SpassCrypto.decrypt(wrapped.toByteArray(StandardCharsets.US_ASCII), "pw".toCharArray())

        assertEquals(payload, String(decrypted, StandardCharsets.UTF_8))
    }

    @Test
    fun `empty file is rejected with a clear message`() {
        val error = assertThrows<SpassException.UnsupportedFileFormat> {
            SpassCrypto.decrypt(ByteArray(0), "pw".toCharArray())
        }
        assertTrue(error.message!!.contains("empty", ignoreCase = true))
    }

    @Test
    fun `non base64 content is rejected as unsupported`() {
        val junk = "this is definitely not a samsung pass export!!!".toByteArray()

        assertThrows<SpassException.UnsupportedFileFormat> {
            SpassCrypto.decrypt(junk, "pw".toCharArray())
        }
    }

    @Test
    fun `truncated container is rejected`() {
        val file = SpassTestFixtures.encrypt(SpassTestFixtures.simplePasswordPayload(), "pw")
        val raw = Base64.getMimeDecoder().decode(file)
        val truncated = Base64.getEncoder().encode(raw.copyOfRange(0, 40))

        assertThrows<SpassException.UnsupportedFileFormat> {
            SpassCrypto.decrypt(truncated, "pw".toCharArray())
        }
    }

    @Test
    fun `looksEncrypted distinguishes containers from plaintext`() {
        val encrypted = SpassTestFixtures.encrypt(SpassTestFixtures.simplePasswordPayload(), "pw")
        assertTrue(SpassCrypto.looksEncrypted(encrypted))
        assertFalse(SpassCrypto.looksEncrypted(SpassTestFixtures.simplePasswordPayload().toByteArray()))
    }
}
