package com.jumbojuice.spasstocsv.core

import java.nio.charset.StandardCharsets
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Decrypts Samsung Pass `.spass` export files.
 *
 * The container is a single Base64 blob whose plaintext bytes are laid out as:
 *
 * ```
 * +----------------+----------------+---------------------------+
 * | salt (20 bytes)| IV   (16 bytes)| AES-256-CBC ciphertext ... |
 * +----------------+----------------+---------------------------+
 * ```
 *
 * The key is `PBKDF2-HMAC-SHA256(password_utf8, salt, 70000 iterations, 32 bytes)`
 * and the ciphertext uses PKCS#7 padding.
 *
 * Everything here runs locally; no key material or plaintext ever leaves the process.
 */
object SpassCrypto {

    const val SALT_LENGTH = 20
    const val IV_LENGTH = 16
    const val PBKDF2_ITERATIONS = 70_000
    const val KEY_LENGTH_BYTES = 32

    /** Smallest possible container: salt + IV + one AES block. */
    private const val MIN_CONTAINER_BYTES = SALT_LENGTH + IV_LENGTH + 16

    private const val HMAC_ALGORITHM = "HmacSHA256"
    private const val HMAC_OUTPUT_BYTES = 32

    /**
     * Decrypts a whole `.spass` file.
     *
     * @param fileBytes the raw bytes of the file as picked by the user.
     * @param password the password typed into Samsung Pass when the export was created.
     * @return the decrypted payload bytes (UTF-8 text).
     */
    @Throws(SpassException::class)
    fun decrypt(fileBytes: ByteArray, password: CharArray): ByteArray {
        val container = decodeContainer(fileBytes)

        if (container.size < MIN_CONTAINER_BYTES) {
            throw SpassException.UnsupportedFileFormat(
                "The file is too small to be a Samsung Pass export " +
                    "(${container.size} bytes after Base64 decoding, at least " +
                    "$MIN_CONTAINER_BYTES expected)."
            )
        }

        val ciphertextLength = container.size - SALT_LENGTH - IV_LENGTH
        if (ciphertextLength % 16 != 0) {
            throw SpassException.UnsupportedFileFormat(
                "The encrypted payload is $ciphertextLength bytes, which is not a whole " +
                    "number of AES blocks. The file looks truncated or is not a .spass export."
            )
        }

        val salt = container.copyOfRange(0, SALT_LENGTH)
        val iv = container.copyOfRange(SALT_LENGTH, SALT_LENGTH + IV_LENGTH)
        val ciphertext = container.copyOfRange(SALT_LENGTH + IV_LENGTH, container.size)

        val passwordBytes = toUtf8Bytes(password)
        val key = try {
            pbkdf2HmacSha256(passwordBytes, salt, PBKDF2_ITERATIONS, KEY_LENGTH_BYTES)
        } finally {
            passwordBytes.fill(0)
        }

        val padded = try {
            // NoPadding, then unpad by hand: a padding error from the JCE is indistinguishable
            // from other failures, and we want to tell the user "wrong password" specifically.
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw SpassException.WrongPasswordOrCorrupt(
                "Could not decrypt the file (${e.javaClass.simpleName}). " +
                    "Check the export password and that the file is complete.",
                e,
            )
        } finally {
            key.fill(0)
        }

        return stripPkcs7Padding(padded)
    }

    /**
     * True when the bytes look like the Base64 container of an encrypted export, i.e.
     * the file still needs a password.
     */
    fun looksEncrypted(fileBytes: ByteArray): Boolean {
        val sample = fileBytes.take(512)
        if (sample.isEmpty()) return false
        var base64Chars = 0
        var meaningful = 0
        for (b in sample) {
            val c = b.toInt().toChar()
            if (c == '\n' || c == '\r' || c == ' ' || c == '\t') continue
            meaningful++
            if (isBase64Char(c)) base64Chars++
        }
        if (meaningful == 0) return false
        return base64Chars.toDouble() / meaningful > 0.95
    }

    private fun isBase64Char(c: Char): Boolean =
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '+' || c == '/' || c == '='

    /** Base64-decodes the whole file, tolerating the line wrapping some exports use. */
    private fun decodeContainer(fileBytes: ByteArray): ByteArray {
        if (fileBytes.isEmpty()) {
            throw SpassException.UnsupportedFileFormat("The file is empty.")
        }
        if (!looksEncrypted(fileBytes)) {
            throw SpassException.UnsupportedFileFormat(
                "The file does not look like a Base64-encoded Samsung Pass export."
            )
        }
        return try {
            // The MIME decoder skips CR/LF, which the strict decoder rejects.
            Base64.getMimeDecoder().decode(fileBytes)
        } catch (e: IllegalArgumentException) {
            throw SpassException.UnsupportedFileFormat(
                "The file is not valid Base64: ${e.message}", e,
            )
        }
    }

    /** Removes PKCS#7 padding, treating any inconsistency as a wrong password. */
    private fun stripPkcs7Padding(data: ByteArray): ByteArray {
        if (data.isEmpty()) {
            throw SpassException.WrongPasswordOrCorrupt("Decryption produced no data.")
        }
        val padLength = data[data.size - 1].toInt() and 0xFF
        if (padLength == 0 || padLength > 16 || padLength > data.size) {
            throw SpassException.WrongPasswordOrCorrupt(
                "Incorrect export password, or the file is corrupt " +
                    "(bad padding length $padLength)."
            )
        }
        for (i in 1..padLength) {
            if ((data[data.size - i].toInt() and 0xFF) != padLength) {
                throw SpassException.WrongPasswordOrCorrupt(
                    "Incorrect export password, or the file is corrupt (inconsistent padding)."
                )
            }
        }
        return data.copyOfRange(0, data.size - padLength)
    }

    /**
     * PBKDF2 with HMAC-SHA256 (RFC 2898).
     *
     * Implemented directly on top of [Mac] rather than going through
     * `SecretKeyFactory("PBKDF2WithHmacSHA256")` for two reasons: that factory is only
     * available from API 26 and, more importantly, providers disagree on how a
     * `char[]` password is turned into bytes. Samsung uses the UTF-8 bytes, so we do
     * that explicitly and get identical results on every platform.
     */
    fun pbkdf2HmacSha256(
        password: ByteArray,
        salt: ByteArray,
        iterations: Int,
        keyLengthBytes: Int,
    ): ByteArray {
        require(iterations > 0) { "iterations must be positive" }
        require(keyLengthBytes > 0) { "keyLengthBytes must be positive" }

        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(password, HMAC_ALGORITHM))

        val output = ByteArray(keyLengthBytes)
        val blockCount = (keyLengthBytes + HMAC_OUTPUT_BYTES - 1) / HMAC_OUTPUT_BYTES
        var offset = 0

        for (block in 1..blockCount) {
            // U1 = PRF(password, salt || INT_BE32(block))
            mac.update(salt)
            mac.update((block ushr 24).toByte())
            mac.update((block ushr 16).toByte())
            mac.update((block ushr 8).toByte())
            mac.update(block.toByte())
            var u = mac.doFinal()
            val accumulator = u.copyOf()

            // Ui = PRF(password, Ui-1); T = U1 xor U2 xor ... xor Uc
            for (i in 2..iterations) {
                u = mac.doFinal(u)
                for (j in accumulator.indices) {
                    accumulator[j] = (accumulator[j].toInt() xor u[j].toInt()).toByte()
                }
            }

            val take = minOf(HMAC_OUTPUT_BYTES, keyLengthBytes - offset)
            System.arraycopy(accumulator, 0, output, offset, take)
            offset += take
        }
        return output
    }

    /** Encodes a password without letting it linger in an intermediate String. */
    private fun toUtf8Bytes(password: CharArray): ByteArray {
        val buffer = StandardCharsets.UTF_8.encode(java.nio.CharBuffer.wrap(password))
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        if (buffer.hasArray()) buffer.array().fill(0)
        return bytes
    }
}
