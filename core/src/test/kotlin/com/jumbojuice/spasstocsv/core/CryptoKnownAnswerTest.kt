package com.jumbojuice.spasstocsv.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pins the cryptographic primitives to published test vectors.
 *
 * The `.spass` round-trip tests encrypt with the same code they decrypt with, so they
 * would pass even if our understanding of the algorithm were wrong. These vectors come
 * from outside the project and rule that out.
 */
class CryptoKnownAnswerTest {

    /** RFC 7914 section 11, PBKDF2-HMAC-SHA256 vector 1. */
    @Test
    fun `pbkdf2 hmac sha256 matches RFC 7914 vector with one iteration`() {
        val actual = SpassCrypto.pbkdf2HmacSha256(
            "passwd".toByteArray(StandardCharsets.UTF_8),
            "salt".toByteArray(StandardCharsets.UTF_8),
            iterations = 1,
            keyLengthBytes = 64,
        )
        val expected = "55ac046e56e3089fec1691c22544b605" +
            "f94185216dde0465e68b9d57c20dacbc" +
            "49ca9cccf179b645991664b39d77ef31" +
            "7c71b845b1e30bd509112041d3a19783"
        assertEquals(expected, SpassTestFixtures.hex(actual))
    }

    /** RFC 7914 section 11, PBKDF2-HMAC-SHA256 vector 2 (80000 iterations). */
    @Test
    fun `pbkdf2 hmac sha256 matches RFC 7914 vector with many iterations`() {
        val actual = SpassCrypto.pbkdf2HmacSha256(
            "Password".toByteArray(StandardCharsets.UTF_8),
            "NaCl".toByteArray(StandardCharsets.UTF_8),
            iterations = 80_000,
            keyLengthBytes = 64,
        )
        val expected = "4ddcd8f60b98be21830cee5ef22701f9" +
            "641a4418d04c0414aeff08876b34ab56" +
            "a1d425a1225833549adb841b51c9b317" +
            "6a272bdebba1d078478f62b397f33c8d"
        assertEquals(expected, SpassTestFixtures.hex(actual))
    }

    /** Cross-check against the JDK's own PBKDF2 implementation. */
    @Test
    fun `pbkdf2 agrees with the jdk implementation`() {
        val password = "correct horse battery staple"
        val salt = SpassTestFixtures.unhex("000102030405060708090a0b0c0d0e0f10111213")

        val ours = SpassCrypto.pbkdf2HmacSha256(
            password.toByteArray(StandardCharsets.UTF_8), salt, 4096, 32,
        )
        val jdk = javax.crypto.SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(
                javax.crypto.spec.PBEKeySpec(password.toCharArray(), salt, 4096, 32 * 8)
            ).encoded

        assertEquals(SpassTestFixtures.hex(jdk), SpassTestFixtures.hex(ours))
    }

    /** NIST SP 800-38A section F.2.5, CBC-AES256.Encrypt. */
    @Test
    fun `aes 256 cbc matches NIST SP 800-38A vector`() {
        val key = SpassTestFixtures.unhex(
            "603deb1015ca71be2b73aef0857d7781" +
                "1f352c073b6108d72d9810a30914dff4"
        )
        val iv = SpassTestFixtures.unhex("000102030405060708090a0b0c0d0e0f")
        val plaintext = SpassTestFixtures.unhex(
            "6bc1bee22e409f96e93d7e117393172a" +
                "ae2d8a571e03ac9c9eb76fac45af8e51"
        )
        val expected = "f58c4c04d6e5f1ba779eabfb5f7bfbd6" +
            "9cfc4e967edb808d679f777bc6702c7d"

        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        assertEquals(expected, SpassTestFixtures.hex(cipher.doFinal(plaintext)))
    }
}
