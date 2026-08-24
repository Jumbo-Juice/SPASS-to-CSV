package com.jumbojuice.spasstocsv.core

/** A parsed record plus the 1-based line it started on, for error reporting. */
data class DelimitedRecord(val fields: List<String>, val startLine: Int)

/**
 * A forgiving RFC 4180-style reader for delimiter-separated text.
 *
 * Samsung Pass writes semicolon-separated tables, but the same reader is used for any
 * delimiter so the app can cope with exports that use commas or tabs. It handles:
 *
 *  - quoted fields, with `""` as an escaped quote;
 *  - delimiters, quotes and newlines inside quoted fields;
 *  - `\r\n`, `\n` and bare `\r` line endings, mixed freely;
 *  - empty fields and empty lines;
 *  - full Unicode, since it works on an already-decoded [String].
 *
 * Malformed input never throws: an unterminated quote is closed at end of input and
 * reported through [warnings] instead.
 */
class DelimitedTextParser(
    private val delimiter: Char = ';',
    private val quote: Char = '"',
    /** When true, blank lines are dropped instead of becoming single empty-field rows. */
    private val skipBlankLines: Boolean = true,
) {

    private val _warnings = mutableListOf<ConversionWarning>()
    val warnings: List<ConversionWarning> get() = _warnings.toList()

    fun parse(text: String, firstLineNumber: Int = 1): List<DelimitedRecord> {
        _warnings.clear()
        val records = mutableListOf<DelimitedRecord>()

        val field = StringBuilder()
        var fields = mutableListOf<String>()
        var inQuotes = false
        var line = firstLineNumber
        var recordStartLine = line
        var recordHasContent = false
        var i = 0

        fun endField() {
            fields.add(field.toString())
            field.setLength(0)
        }

        fun endRecord() {
            endField()
            val blank = fields.size == 1 && fields[0].isEmpty()
            if (!(blank && skipBlankLines && !recordHasContent)) {
                records.add(DelimitedRecord(fields, recordStartLine))
            }
            fields = mutableListOf()
            recordHasContent = false
            recordStartLine = line
        }

        while (i < text.length) {
            val c = text[i]

            if (inQuotes) {
                when {
                    c == quote && i + 1 < text.length && text[i + 1] == quote -> {
                        field.append(quote)
                        i += 2
                        continue
                    }
                    c == quote -> {
                        inQuotes = false
                        i++
                        continue
                    }
                    c == '\r' -> {
                        // Normalise line endings inside quoted values.
                        field.append('\n')
                        line++
                        i += if (i + 1 < text.length && text[i + 1] == '\n') 2 else 1
                        continue
                    }
                    c == '\n' -> {
                        field.append('\n')
                        line++
                        i++
                        continue
                    }
                    else -> {
                        field.append(c)
                        i++
                        continue
                    }
                }
            }

            when {
                c == quote && field.isEmpty() -> {
                    inQuotes = true
                    recordHasContent = true
                    i++
                }
                c == quote -> {
                    // A quote in the middle of an unquoted value: keep it verbatim rather
                    // than guessing, so no characters are lost.
                    field.append(c)
                    recordHasContent = true
                    i++
                }
                c == delimiter -> {
                    endField()
                    recordHasContent = true
                    i++
                }
                c == '\r' -> {
                    line++
                    i += if (i + 1 < text.length && text[i + 1] == '\n') 2 else 1
                    endRecord()
                }
                c == '\n' -> {
                    line++
                    i++
                    endRecord()
                }
                else -> {
                    field.append(c)
                    recordHasContent = true
                    i++
                }
            }
        }

        if (inQuotes) {
            _warnings.add(
                ConversionWarning(
                    ConversionWarning.Kind.UNTERMINATED_QUOTE,
                    "A quoted value was never closed; it was read to the end of the table.",
                    recordStartLine,
                )
            )
        }

        // Flush whatever is left, unless the input ended exactly on a line break.
        if (field.isNotEmpty() || fields.isNotEmpty() || recordHasContent) {
            endRecord()
        }

        return records
    }
}
