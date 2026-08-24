package com.jumbojuice.spasstocsv.core

/**
 * One table extracted from a Samsung Pass export.
 *
 * A `.spass` file may contain up to four tables (passwords, cards, addresses and
 * notes). Each is an independent grid with its own header row, so each maps to its
 * own CSV file.
 */
data class SpassTable(
    /** Canonical table name, e.g. `passwords`. See [SpassTableType]. */
    val name: String,
    val type: SpassTableType,
    /** Column names, taken verbatim from the export's plain-text header row. */
    val headers: List<String>,
    /** Data rows. Every row is padded/truncated to `headers.size` by the parser. */
    val rows: List<List<String>>,
) {
    val rowCount: Int get() = rows.size
    val columnCount: Int get() = headers.size
    val isEmpty: Boolean get() = rows.isEmpty()
}

/** Known Samsung Pass table kinds, identified from the header row. */
enum class SpassTableType(val canonicalName: String) {
    PASSWORDS("passwords"),
    CARDS("cards"),
    ADDRESSES("addresses"),
    NOTES("notes"),
    UNKNOWN("table"),
}

/** A non-fatal problem found while converting. Surfaced to the user, never thrown. */
data class ConversionWarning(
    val kind: Kind,
    val message: String,
    /** 1-based line number in the decrypted payload, when known. */
    val line: Int? = null,
) {
    enum class Kind {
        MALFORMED_ROW,
        UNDECODABLE_FIELD,
        UNEXPECTED_PREAMBLE,
        UNKNOWN_TABLE,
        EMPTY_TABLE,
        UNTERMINATED_QUOTE,
        TRAILING_DATA,
    }

    override fun toString(): String =
        if (line != null) "line $line: $message" else message
}

/** The fully parsed contents of a `.spass` export. */
data class SpassDocument(
    /** Export format version from line 1, or `null` when it was not a number. */
    val formatVersion: Int?,
    /** Module flags from line 2: passwords, cards, addresses, notes. */
    val moduleFlags: List<Boolean>,
    /** Any additional preamble lines (format v25+ adds one). Kept so nothing is lost. */
    val preambleExtras: List<String>,
    val tables: List<SpassTable>,
    val warnings: List<ConversionWarning>,
) {
    val totalRows: Int get() = tables.sumOf { it.rowCount }

    /** Tables that actually carry rows, most useful first. */
    fun nonEmptyTables(): List<SpassTable> = tables.filterNot { it.isEmpty }
}

/**
 * Everything that can go wrong is reported as one of these, so the UI can show a
 * specific, actionable message instead of a stack trace.
 */
sealed class SpassException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The bytes are not a Samsung Pass export at all. */
    class UnsupportedFileFormat(message: String, cause: Throwable? = null) :
        SpassException(message, cause)

    /** The file could not be read from storage. */
    class UnableToRead(message: String, cause: Throwable? = null) :
        SpassException(message, cause)

    /** Decryption produced garbage -- almost always a wrong export password. */
    class WrongPasswordOrCorrupt(message: String, cause: Throwable? = null) :
        SpassException(message, cause)

    /** Decryption succeeded but the payload is not shaped like a `.spass` export. */
    class InvalidStructure(message: String, cause: Throwable? = null) :
        SpassException(message, cause)

    /** The CSV could not be produced or written. */
    class CsvGenerationFailure(message: String, cause: Throwable? = null) :
        SpassException(message, cause)
}
