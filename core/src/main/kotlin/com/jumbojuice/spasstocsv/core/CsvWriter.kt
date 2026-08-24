package com.jumbojuice.spasstocsv.core

import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets

/** Formatting choices for the generated CSV. */
data class CsvOptions(
    val delimiter: Char = ',',
    /** RFC 4180 specifies CRLF. Most importers accept either. */
    val lineSeparator: String = "\r\n",
    /** A UTF-8 BOM helps Excel detect the encoding, but confuses some importers. */
    val writeByteOrderMark: Boolean = false,
    val includeHeader: Boolean = true,
)

/**
 * Writes RFC 4180-compliant CSV.
 *
 * A field is quoted when it contains the delimiter, a double quote, CR, LF, or has
 * leading/trailing whitespace that would otherwise be lost. Embedded quotes are
 * doubled. Output is always UTF-8, so Unicode survives intact.
 */
object CsvWriter {

    /** Renders a whole table to a CSV string. */
    @Throws(SpassException::class)
    fun write(table: SpassTable, options: CsvOptions = CsvOptions()): String {
        val builder = StringBuilder()
        try {
            if (options.writeByteOrderMark) builder.append('﻿')
            if (options.includeHeader && table.headers.isNotEmpty()) {
                appendRow(builder, table.headers, options)
            }
            for (row in table.rows) {
                appendRow(builder, row, options)
            }
        } catch (e: Exception) {
            throw SpassException.CsvGenerationFailure(
                "Failed to generate CSV for table '${table.name}': ${e.message}", e,
            )
        }
        return builder.toString()
    }

    /** Streams a table straight to an [OutputStream], for large exports. */
    @Throws(SpassException::class)
    fun writeTo(table: SpassTable, out: OutputStream, options: CsvOptions = CsvOptions()) {
        try {
            OutputStreamWriter(out, StandardCharsets.UTF_8).use { writer ->
                if (options.writeByteOrderMark) writer.write("﻿")
                val line = StringBuilder()
                if (options.includeHeader && table.headers.isNotEmpty()) {
                    appendRow(line, table.headers, options)
                    writer.write(line.toString())
                    line.setLength(0)
                }
                for (row in table.rows) {
                    appendRow(line, row, options)
                    writer.write(line.toString())
                    line.setLength(0)
                }
                writer.flush()
            }
        } catch (e: Exception) {
            throw SpassException.CsvGenerationFailure(
                "Failed to write CSV for table '${table.name}': ${e.message}", e,
            )
        }
    }

    private fun appendRow(builder: StringBuilder, row: List<String>, options: CsvOptions) {
        for ((index, value) in row.withIndex()) {
            if (index > 0) builder.append(options.delimiter)
            builder.append(escape(value, options.delimiter))
        }
        builder.append(options.lineSeparator)
    }

    /** Quotes and escapes a single field. Public so tests can pin the exact behaviour. */
    fun escape(value: String, delimiter: Char = ','): String {
        val needsQuoting = value.any { it == delimiter || it == '"' || it == '\r' || it == '\n' } ||
            (value.isNotEmpty() && (value.first().isWhitespace() || value.last().isWhitespace()))

        if (!needsQuoting) return value

        val builder = StringBuilder(value.length + 2)
        builder.append('"')
        for (c in value) {
            if (c == '"') builder.append('"')
            builder.append(c)
        }
        builder.append('"')
        return builder.toString()
    }
}
