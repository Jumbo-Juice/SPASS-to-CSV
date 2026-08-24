package com.jumbojuice.spasstocsv.core

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Parses the decrypted payload of a Samsung Pass export.
 *
 * Layout of the plaintext:
 *
 * ```
 * 25                       <- line 1: export format version
 * true;false;false;true    <- line 2: which modules were exported
 * false                    <- line 3: added in format v25, meaning unknown
 * next_table               <- table separator keyword, on a line of its own
 * id;origin_url;...        <- header row, plain text, semicolon separated
 * MQ==;aHR0cHM6...         <- data rows, semicolon separated, every field Base64
 * next_table
 * ...
 * ```
 *
 * The parser is deliberately defensive: a row with the wrong number of fields, a field
 * that is not valid Base64, or an unexpected extra table are all recorded as warnings
 * and the rest of the file is still converted.
 */
object SpassParser {

    /** Line that separates one table from the next. */
    const val TABLE_SEPARATOR = "next_table"

    /** Samsung writes this in place of a SQL NULL. */
    const val NULL_SENTINEL = "&&&NULL&&&"

    /** Module flag order on line 2. */
    private val MODULE_ORDER = listOf(
        SpassTableType.PASSWORDS,
        SpassTableType.CARDS,
        SpassTableType.ADDRESSES,
        SpassTableType.NOTES,
    )

    /**
     * Header column names that identify each table. Matching on content rather than on
     * position keeps the parser correct when only some modules were exported.
     */
    private val TABLE_SIGNATURES = mapOf(
        SpassTableType.PASSWORDS to setOf("origin_url", "password_value", "username_value"),
        SpassTableType.CARDS to setOf("card_number_encrypted", "first_six_digit", "name_on_card"),
        SpassTableType.ADDRESSES to setOf("street_address", "zipcode", "country_code"),
        SpassTableType.NOTES to setOf("note_title", "note_details"),
    )

    /**
     * @param delimiter field separator used inside the export's tables.
     * @param replaceNullSentinel map [NULL_SENTINEL] to an empty string.
     */
    data class Options(
        val delimiter: Char = ';',
        val replaceNullSentinel: Boolean = true,
    )

    @Throws(SpassException::class)
    fun parse(payload: ByteArray, options: Options = Options()): SpassDocument =
        parse(decodeUtf8(payload), options)

    @Throws(SpassException::class)
    fun parse(payloadText: String, options: Options): SpassDocument {
        val warnings = mutableListOf<ConversionWarning>()
        val text = payloadText.removePrefix("﻿")

        if (text.isBlank()) {
            throw SpassException.InvalidStructure("The decrypted export is empty.")
        }

        val lines = text.split(Regex("\r\n|\r|\n"))

        // ---- preamble -------------------------------------------------------
        var index = 0
        while (index < lines.size && lines[index].trim() != TABLE_SEPARATOR) index++

        if (index == lines.size) {
            throw SpassException.InvalidStructure(
                "No '$TABLE_SEPARATOR' marker was found. The decrypted data does not look " +
                    "like a Samsung Pass export -- if the password was wrong the result " +
                    "would be random bytes."
            )
        }

        val preamble = lines.subList(0, index)
        val formatVersion = preamble.getOrNull(0)?.trim()?.toIntOrNull()
        if (formatVersion == null && preamble.isNotEmpty()) {
            warnings += ConversionWarning(
                ConversionWarning.Kind.UNEXPECTED_PREAMBLE,
                "Line 1 was '${preamble[0].trim().take(40)}', expected a numeric format version.",
                1,
            )
        }

        val moduleFlags = preamble.getOrNull(1)
            ?.split(options.delimiter)
            ?.map { it.trim().equals("true", ignoreCase = true) }
            ?: emptyList()

        val preambleExtras = if (preamble.size > 2) preamble.subList(2, preamble.size).toList() else emptyList()

        // ---- split into table sections --------------------------------------
        val sections = mutableListOf<Section>()
        var current: MutableList<String>? = null
        var currentStart = 0

        for (i in index until lines.size) {
            val line = lines[i]
            if (line.trim() == TABLE_SEPARATOR) {
                current?.let { sections += Section(it, currentStart) }
                current = mutableListOf()
                currentStart = i + 2 // 1-based line number of the section's first line
            } else {
                current?.add(line)
            }
        }
        current?.let { sections += Section(it, currentStart) }

        // ---- parse each section ---------------------------------------------
        val tables = mutableListOf<SpassTable>()
        val enabledTypes = MODULE_ORDER.filterIndexed { i, _ -> moduleFlags.getOrElse(i) { false } }
        var positionalIndex = 0

        for (section in sections) {
            val table = parseSection(section, options, warnings) { headers ->
                identifyTable(headers, enabledTypes, positionalIndex).also { positionalIndex++ }
            } ?: continue
            tables += table
        }

        if (tables.isEmpty()) {
            throw SpassException.InvalidStructure(
                "The export contains no readable tables."
            )
        }

        for (table in tables) {
            if (table.isEmpty) {
                warnings += ConversionWarning(
                    ConversionWarning.Kind.EMPTY_TABLE,
                    "Table '${table.name}' has column headers but no rows.",
                )
            }
        }

        return SpassDocument(
            formatVersion = formatVersion,
            moduleFlags = moduleFlags,
            preambleExtras = preambleExtras,
            tables = tables,
            warnings = warnings,
        )
    }

    private class Section(val lines: List<String>, val startLine: Int)

    private fun parseSection(
        section: Section,
        options: Options,
        warnings: MutableList<ConversionWarning>,
        identify: (List<String>) -> SpassTableType,
    ): SpassTable? {
        // A blank line terminates a table's data; anything after it is unexpected.
        val body = section.lines.toMutableList()
        while (body.isNotEmpty() && body.last().isBlank()) body.removeAt(body.size - 1)
        if (body.isEmpty()) return null

        val blankAt = body.indexOfFirst { it.isBlank() }
        if (blankAt >= 0) {
            warnings += ConversionWarning(
                ConversionWarning.Kind.TRAILING_DATA,
                "Unexpected blank line inside a table; the following rows were kept anyway.",
                section.startLine + blankAt,
            )
            body.removeAll { it.isBlank() }
        }

        val parser = DelimitedTextParser(delimiter = options.delimiter)
        val records = parser.parse(body.joinToString("\n"), firstLineNumber = section.startLine)
        warnings += parser.warnings

        if (records.isEmpty()) return null

        val headers = records.first().fields.map { it.trim() }
        val type = identify(headers)
        val expectedColumns = headers.size

        val rows = mutableListOf<List<String>>()
        for (record in records.drop(1)) {
            val decoded = record.fields.mapIndexed { column, raw ->
                decodeField(raw, record.startLine, column, headers, options, warnings)
            }

            val normalised = when {
                decoded.size == expectedColumns -> decoded
                decoded.size < expectedColumns -> {
                    warnings += ConversionWarning(
                        ConversionWarning.Kind.MALFORMED_ROW,
                        "Row has ${decoded.size} of $expectedColumns columns; " +
                            "the missing ones were left empty.",
                        record.startLine,
                    )
                    decoded + List(expectedColumns - decoded.size) { "" }
                }
                else -> {
                    warnings += ConversionWarning(
                        ConversionWarning.Kind.MALFORMED_ROW,
                        "Row has ${decoded.size} columns, more than the $expectedColumns in " +
                            "the header; the extra values were kept in overflow columns.",
                        record.startLine,
                    )
                    decoded
                }
            }
            rows += normalised
        }

        // Widen the header if any row overflowed, so no value is dropped from the CSV.
        val widest = rows.maxOfOrNull { it.size } ?: expectedColumns
        val finalHeaders = if (widest > expectedColumns) {
            headers + (expectedColumns until widest).map { "extra_column_${it + 1}" }
        } else {
            headers
        }
        val paddedRows = rows.map { row ->
            if (row.size < finalHeaders.size) row + List(finalHeaders.size - row.size) { "" } else row
        }

        if (type == SpassTableType.UNKNOWN) {
            warnings += ConversionWarning(
                ConversionWarning.Kind.UNKNOWN_TABLE,
                "A table with unrecognised columns was found; it was exported as-is.",
                section.startLine,
            )
        }

        return SpassTable(
            name = type.canonicalName,
            type = type,
            headers = finalHeaders,
            rows = paddedRows,
        )
    }

    private fun decodeField(
        raw: String,
        line: Int,
        column: Int,
        headers: List<String>,
        options: Options,
        warnings: MutableList<ConversionWarning>,
    ): String {
        if (raw.isEmpty()) return ""

        val decoded = try {
            String(Base64.getDecoder().decode(raw), StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            warnings += ConversionWarning(
                ConversionWarning.Kind.UNDECODABLE_FIELD,
                "Column '${headers.getOrNull(column) ?: column + 1}' is not valid Base64; " +
                    "the raw value was kept.",
                line,
            )
            return raw
        }

        return if (options.replaceNullSentinel && decoded == NULL_SENTINEL) "" else decoded
    }

    /**
     * Names a table from its header row, falling back to the export's module flags and
     * finally to a generic numbered name.
     */
    private fun identifyTable(
        headers: List<String>,
        enabledTypes: List<SpassTableType>,
        positionalIndex: Int,
    ): SpassTableType {
        val lowered = headers.map { it.lowercase() }.toSet()
        for ((type, signature) in TABLE_SIGNATURES) {
            if (signature.any { it in lowered }) return type
        }
        return enabledTypes.getOrNull(positionalIndex) ?: SpassTableType.UNKNOWN
    }

    private fun decodeUtf8(payload: ByteArray): String =
        String(payload, StandardCharsets.UTF_8)
}
