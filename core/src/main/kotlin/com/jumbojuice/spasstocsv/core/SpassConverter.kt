package com.jumbojuice.spasstocsv.core

import java.nio.charset.StandardCharsets

/**
 * The one entry point the app uses: bytes in, tables out.
 *
 * Handles both shapes a `.spass` file can arrive in -- the normal encrypted container,
 * and an already-decrypted payload (useful for testing and for files produced by other
 * tools). Nothing here touches the network or the filesystem.
 */
object SpassConverter {

    /** Extensions the file picker suggests. `.spass` is the one Samsung Pass writes. */
    val SUPPORTED_EXTENSIONS = listOf("spass", "txt", "csv", "dat")

    /** Refuse absurd inputs rather than running the device out of memory. */
    const val MAX_INPUT_BYTES = 64L * 1024 * 1024

    /**
     * True when [fileBytes] is an encrypted container and a password is required.
     * An already-decrypted payload returns false.
     */
    fun requiresPassword(fileBytes: ByteArray): Boolean {
        if (looksLikePlaintextExport(fileBytes)) return false
        return SpassCrypto.looksEncrypted(fileBytes)
    }

    /**
     * Decrypts (when needed) and parses a `.spass` file.
     *
     * @param password the export password; may be null/empty for an already-decrypted file.
     */
    @Throws(SpassException::class)
    fun convert(
        fileBytes: ByteArray,
        password: CharArray? = null,
        options: SpassParser.Options = SpassParser.Options(),
    ): SpassDocument {
        if (fileBytes.isEmpty()) {
            throw SpassException.UnsupportedFileFormat(
                "The selected file is empty, so there is nothing to convert."
            )
        }
        if (fileBytes.size > MAX_INPUT_BYTES) {
            throw SpassException.UnableToRead(
                "The file is ${fileBytes.size / (1024 * 1024)} MB, larger than the " +
                    "${MAX_INPUT_BYTES / (1024 * 1024)} MB limit."
            )
        }

        if (looksLikePlaintextExport(fileBytes)) {
            return SpassParser.parse(fileBytes, options)
        }

        if (password == null || password.isEmpty()) {
            throw SpassException.WrongPasswordOrCorrupt(
                "This export is encrypted. Enter the password you set in Samsung Pass " +
                    "when you created the file."
            )
        }

        val payload = SpassCrypto.decrypt(fileBytes, password)
        return SpassParser.parse(payload, options)
    }

    /** Renders one table as CSV text. */
    @Throws(SpassException::class)
    fun toCsv(table: SpassTable, options: CsvOptions = CsvOptions()): String =
        CsvWriter.write(table, options)

    /**
     * Suggests an output file name, e.g. `MyVault.spass` + `passwords` ->
     * `MyVault-passwords.csv`.
     */
    fun suggestCsvName(sourceName: String?, tableName: String): String {
        val base = (sourceName ?: "spass-export")
            .substringAfterLast('/')
            .substringBeforeLast('.')
            .ifBlank { "spass-export" }
            .replace(Regex("[^A-Za-z0-9 ._-]"), "_")
            .take(60)
        return "$base-$tableName.csv"
    }

    /**
     * Cheap check for a payload that is already decrypted: the separator keyword uses an
     * underscore, which cannot appear in standard Base64, so its presence is decisive.
     */
    private fun looksLikePlaintextExport(fileBytes: ByteArray): Boolean {
        val head = String(
            fileBytes.copyOfRange(0, minOf(fileBytes.size, 4096)),
            StandardCharsets.UTF_8,
        )
        return head.lineSequence().any { it.trim() == SpassParser.TABLE_SEPARATOR }
    }
}
