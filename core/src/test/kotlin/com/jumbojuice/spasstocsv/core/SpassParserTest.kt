package com.jumbojuice.spasstocsv.core

import com.jumbojuice.spasstocsv.core.SpassTestFixtures.ADDRESS_HEADERS
import com.jumbojuice.spasstocsv.core.SpassTestFixtures.CARD_HEADERS
import com.jumbojuice.spasstocsv.core.SpassTestFixtures.NOTE_HEADERS
import com.jumbojuice.spasstocsv.core.SpassTestFixtures.PASSWORD_HEADERS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class SpassParserTest {

    private fun parse(payload: String) = SpassParser.parse(payload, SpassParser.Options())

    private fun passwordRow(
        id: String = "1",
        url: String = "https://example.com",
        user: String = "alice",
        password: String = "hunter2",
        title: String = "Example",
        memo: String = "",
    ): List<String> {
        val row = MutableList(PASSWORD_HEADERS.size) { "" }
        row[0] = id
        row[1] = url
        row[4] = user
        row[7] = password
        row[17] = title
        row[31] = memo
        return row
    }

    @Test
    fun `parses a normal single row export`() {
        val document = parse(SpassTestFixtures.simplePasswordPayload())

        assertEquals(25, document.formatVersion)
        assertEquals(listOf(true, false, false, false), document.moduleFlags)
        assertEquals(listOf("false"), document.preambleExtras)
        assertEquals(1, document.tables.size)

        val table = document.tables.single()
        assertEquals("passwords", table.name)
        assertEquals(SpassTableType.PASSWORDS, table.type)
        assertEquals(PASSWORD_HEADERS, table.headers)
        assertEquals(1, table.rowCount)
        assertEquals("https://example.com/login", table.rows[0][1])
        assertEquals("alice@example.com", table.rows[0][4])
        assertEquals("hunter2", table.rows[0][7])
    }

    @Test
    fun `parses multiple rows`() {
        val rows = (1..25).map { passwordRow(id = it.toString(), user = "user$it") }
        val document = parse(SpassTestFixtures.buildPayload(tables = listOf(PASSWORD_HEADERS to rows)))

        assertEquals(25, document.tables.single().rowCount)
        assertEquals("user25", document.tables.single().rows[24][4])
    }

    @Test
    fun `parses all four tables and names them from their headers`() {
        val document = parse(
            SpassTestFixtures.buildPayload(
                modules = listOf(true, true, true, true),
                tables = listOf(
                    PASSWORD_HEADERS to listOf(passwordRow()),
                    CARD_HEADERS to listOf(MutableList(CARD_HEADERS.size) { "c$it" }),
                    ADDRESS_HEADERS to listOf(MutableList(ADDRESS_HEADERS.size) { "a$it" }),
                    NOTE_HEADERS to listOf(listOf("1", "Title", "Body", "1700000000")),
                ),
            )
        )

        assertEquals(
            listOf("passwords", "cards", "addresses", "notes"),
            document.tables.map { it.name },
        )
        assertEquals("Body", document.tables[3].rows[0][2])
    }

    @Test
    fun `identifies tables by header even when module flags are misleading`() {
        // Only the notes module is flagged, but the file actually holds the notes table.
        val document = parse(
            SpassTestFixtures.buildPayload(
                modules = listOf(false, false, false, true),
                tables = listOf(NOTE_HEADERS to listOf(listOf("1", "T", "D", "0"))),
            )
        )
        assertEquals(SpassTableType.NOTES, document.tables.single().type)
    }

    @Test
    fun `handles a table with headers but no rows`() {
        val document = parse(SpassTestFixtures.buildPayload(tables = listOf(PASSWORD_HEADERS to emptyList())))

        assertEquals(0, document.tables.single().rowCount)
        assertEquals(PASSWORD_HEADERS.size, document.tables.single().columnCount)
        assertTrue(document.warnings.any { it.kind == ConversionWarning.Kind.EMPTY_TABLE })
    }

    @Test
    fun `preserves empty fields`() {
        val document = parse(
            SpassTestFixtures.buildPayload(
                tables = listOf(NOTE_HEADERS to listOf(listOf("1", "", "", "0")))
            )
        )
        assertEquals(listOf("1", "", "", "0"), document.tables.single().rows[0])
    }

    @Test
    fun `preserves unicode values`() {
        val document = parse(
            SpassTestFixtures.buildPayload(
                tables = listOf(NOTE_HEADERS to listOf(listOf("1", "日本語 タイトル", "emoji 🔐 café", "0")))
            )
        )
        assertEquals("日本語 タイトル", document.tables.single().rows[0][1])
        assertEquals("emoji 🔐 café", document.tables.single().rows[0][2])
    }

    @Test
    fun `preserves commas semicolons and quotes inside values`() {
        val nasty = "value, with; separators and \"quotes\" and 'apostrophes'"
        val document = parse(
            SpassTestFixtures.buildPayload(
                tables = listOf(NOTE_HEADERS to listOf(listOf("1", nasty, "line1\nline2", "0")))
            )
        )
        assertEquals(nasty, document.tables.single().rows[0][1])
        assertEquals("line1\nline2", document.tables.single().rows[0][2])
    }

    @Test
    fun `maps the NULL sentinel to an empty value`() {
        val document = parse(
            SpassTestFixtures.buildPayload(
                tables = listOf(NOTE_HEADERS to listOf(listOf("1", "&&&NULL&&&", "body", "0")))
            )
        )
        assertEquals("", document.tables.single().rows[0][1])
    }

    @Test
    fun `keeps the NULL sentinel when the option is disabled`() {
        val payload = SpassTestFixtures.buildPayload(
            tables = listOf(NOTE_HEADERS to listOf(listOf("1", "&&&NULL&&&", "b", "0")))
        )
        val document = SpassParser.parse(payload, SpassParser.Options(replaceNullSentinel = false))
        assertEquals("&&&NULL&&&", document.tables.single().rows[0][1])
    }

    @Test
    fun `handles all three line endings`() {
        for (separator in listOf("\n", "\r\n", "\r")) {
            val document = parse(SpassTestFixtures.simplePasswordPayload(lineSeparator = separator))
            assertEquals(1, document.tables.single().rowCount, "failed for separator ${separator.map { it.code }}")
            assertEquals("hunter2", document.tables.single().rows[0][7])
        }
    }

    @Test
    fun `a short row is padded and reported`() {
        val payload = SpassTestFixtures.buildPayload(
            tables = listOf(NOTE_HEADERS to emptyList())
        ) + "MQ==;dGl0bGU=\n" // only two of four columns

        val document = parse(payload)

        assertEquals(listOf("1", "title", "", ""), document.tables.single().rows[0])
        assertTrue(document.warnings.any { it.kind == ConversionWarning.Kind.MALFORMED_ROW })
    }

    @Test
    fun `a long row keeps its extra values in overflow columns`() {
        val payload = SpassTestFixtures.buildPayload(
            tables = listOf(NOTE_HEADERS to emptyList())
        ) + "MQ==;dA==;ZA==;MA==;ZXh0cmE=\n" // five values for four columns

        val document = parse(payload)
        val table = document.tables.single()

        assertEquals(5, table.columnCount)
        assertEquals("extra_column_5", table.headers[4])
        assertEquals("extra", table.rows[0][4])
        assertTrue(document.warnings.any { it.kind == ConversionWarning.Kind.MALFORMED_ROW })
    }

    @Test
    fun `a field that is not valid base64 is kept verbatim and reported`() {
        val payload = SpassTestFixtures.buildPayload(
            tables = listOf(NOTE_HEADERS to emptyList())
        ) + "MQ==;!!!not base64!!!;ZA==;MA==\n"

        val document = parse(payload)

        assertEquals("!!!not base64!!!", document.tables.single().rows[0][1])
        assertTrue(document.warnings.any { it.kind == ConversionWarning.Kind.UNDECODABLE_FIELD })
    }

    @Test
    fun `an unrecognised table is still exported and flagged`() {
        val document = parse(
            SpassTestFixtures.buildPayload(
                modules = listOf(false, false, false, false),
                tables = listOf(listOf("alpha", "beta") to listOf(listOf("1", "2"))),
            )
        )

        assertEquals(SpassTableType.UNKNOWN, document.tables.single().type)
        assertEquals(listOf("1", "2"), document.tables.single().rows[0])
        assertTrue(document.warnings.any { it.kind == ConversionWarning.Kind.UNKNOWN_TABLE })
    }

    @Test
    fun `a non numeric version line is reported but does not stop parsing`() {
        val payload = "vNEXT\ntrue\nfalse\nnext_table\n" + NOTE_HEADERS.joinToString(";") + "\nMQ==;dA==;ZA==;MA==\n"

        val document = parse(payload)

        assertEquals(null, document.formatVersion)
        assertEquals(1, document.tables.single().rowCount)
        assertTrue(document.warnings.any { it.kind == ConversionWarning.Kind.UNEXPECTED_PREAMBLE })
    }

    @Test
    fun `payload without a next_table marker is rejected`() {
        val error = assertThrows<SpassException.InvalidStructure> {
            parse("25\ntrue;false;false;false\nfalse\nid;title\n1;2\n")
        }
        assertTrue(error.message!!.contains("next_table"))
    }

    @Test
    fun `blank payload is rejected`() {
        assertThrows<SpassException.InvalidStructure> { parse("   \n\n") }
    }

    @Test
    fun `separator with no table after it yields no tables`() {
        assertThrows<SpassException.InvalidStructure> { parse("25\ntrue\nfalse\nnext_table\n") }
    }

    @Test
    fun `a byte order mark is tolerated`() {
        val document = parse("﻿" + SpassTestFixtures.simplePasswordPayload())
        assertNotNull(document.tables.single())
    }

    @Test
    fun `handles a large export`() {
        val rows = (1..20_000).map { passwordRow(id = it.toString(), user = "user$it", password = "pw-$it") }
        val payload = SpassTestFixtures.buildPayload(tables = listOf(PASSWORD_HEADERS to rows))

        val document = parse(payload)
        val table = document.tables.single()

        assertEquals(20_000, table.rowCount)
        assertEquals("pw-20000", table.rows[19_999][7])
    }
}
