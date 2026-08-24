package com.jumbojuice.spasstocsv.core

import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Test-only helpers that build `.spass` files.
 *
 * [encrypt] is the exact inverse of [SpassCrypto.decrypt], which lets the tests build
 * realistic fixtures. Because a round trip against our own code could hide a shared
 * misunderstanding, the primitives themselves are separately pinned to published
 * NIST/RFC test vectors in `CryptoKnownAnswerTest`.
 */
object SpassTestFixtures {

    /** Real column names from a Samsung Pass export, in order. */
    val PASSWORD_HEADERS = listOf(
        "id", "origin_url", "action_url", "username_element", "username_value",
        "id_tz_enc", "password_element", "password_value", "pw_tz_enc", "host_url",
        "ssl_valid", "preferred", "blacklisted_by_user", "use_additional_auth",
        "cm_api_support", "created_time", "modified_time", "title", "favicon",
        "source_type", "app_name", "package_name", "package_signature",
        "reserved_1", "reserved_2", "reserved_3", "reserved_4", "reserved_5",
        "reserved_6", "reserved_7", "reserved_8", "credential_memo", "otp",
        "root_id", "parent_id",
    )

    val NOTE_HEADERS = listOf("id", "note_title", "note_details", "date_modified")

    val CARD_HEADERS = listOf(
        "id", "card_number_encrypted", "card_security_code", "first_six_digit",
        "last_four_digit", "name_on_card", "expiration_month", "expiration_year",
        "billing_address_id", "vault_status", "date_modified", "reserved_4",
        "reserved_5", "reserved_6", "is_encrypted",
    )

    val ADDRESS_HEADERS = listOf(
        "id", "full_name", "company_name", "street_address", "city", "state",
        "zipcode", "country_code", "phone_number", "email", "date_modified",
        "reserved_4", "reserved_5", "reserved_6",
    )

    private fun b64(value: String): String =
        Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    /** Builds a decrypted payload from already-plain values, Base64-encoding each field. */
    fun buildPayload(
        version: Int = 25,
        modules: List<Boolean> = listOf(true, false, false, false),
        extraPreamble: List<String> = listOf("false"),
        tables: List<Pair<List<String>, List<List<String>>>>,
        lineSeparator: String = "\n",
        encodeFields: Boolean = true,
    ): String {
        val out = StringBuilder()
        out.append(version).append(lineSeparator)
        out.append(modules.joinToString(";") { it.toString() }).append(lineSeparator)
        for (extra in extraPreamble) out.append(extra).append(lineSeparator)
        for ((headers, rows) in tables) {
            out.append(SpassParser.TABLE_SEPARATOR).append(lineSeparator)
            out.append(headers.joinToString(";")).append(lineSeparator)
            for (row in rows) {
                out.append(row.joinToString(";") { if (encodeFields) b64(it) else it })
                out.append(lineSeparator)
            }
        }
        return out.toString()
    }

    /** A small but realistic single-password export payload. */
    fun simplePasswordPayload(lineSeparator: String = "\n"): String {
        val row = MutableList(PASSWORD_HEADERS.size) { "" }
        row[0] = "1"
        row[1] = "https://example.com/login"
        row[4] = "alice@example.com"
        row[7] = "hunter2"
        row[17] = "Example"
        row[31] = "my notes"
        row[32] = "&&&NULL&&&"
        return buildPayload(
            tables = listOf(PASSWORD_HEADERS to listOf(row)),
            lineSeparator = lineSeparator,
        )
    }

    /** Encrypts a payload into the `.spass` container format. Inverse of the decryptor. */
    fun encrypt(payload: String, password: String, salt: ByteArray? = null, iv: ByteArray? = null): ByteArray {
        val random = SecureRandom()
        val actualSalt = salt ?: ByteArray(SpassCrypto.SALT_LENGTH).also { random.nextBytes(it) }
        val actualIv = iv ?: ByteArray(SpassCrypto.IV_LENGTH).also { random.nextBytes(it) }

        val key = SpassCrypto.pbkdf2HmacSha256(
            password.toByteArray(StandardCharsets.UTF_8),
            actualSalt,
            SpassCrypto.PBKDF2_ITERATIONS,
            SpassCrypto.KEY_LENGTH_BYTES,
        )

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(actualIv))
        val ciphertext = cipher.doFinal(payload.toByteArray(StandardCharsets.UTF_8))

        val container = actualSalt + actualIv + ciphertext
        return Base64.getEncoder().encode(container)
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(hex: String): ByteArray {
        val clean = hex.replace(Regex("\\s"), "")
        return ByteArray(clean.length / 2) {
            ((Character.digit(clean[it * 2], 16) shl 4) or Character.digit(clean[it * 2 + 1], 16)).toByte()
        }
    }
}
