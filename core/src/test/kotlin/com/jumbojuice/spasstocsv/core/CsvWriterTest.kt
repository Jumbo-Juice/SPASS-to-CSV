package com.jumbojuice.spasstocsv.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

class CsvWriterTest {

    private fun table(headers: List<String>, vararg rows: List<String>) =
        SpassTable("t", SpassTableType.UNKNOWN, headers, rows.toList())

    @Test
    fun `writes header and rows with CRLF`() {
        val csv = CsvWriter.write(table(listOf("a", "b"), listOf("1", "2")))
        assertEquals("a,b\r\n1,2\r\n", csv)
    }

    @Test
    fun `quotes fields containing the delimiter`() {
        assertEquals("\"a,b\"", CsvWriter.escape("a,b"))
    }

    @Test
    fun `doubles embedded quotes`() {
        assertEquals("\"say \"\"hi\"\"\"", CsvWriter.escape("say \"hi\""))
    }

    @Test
    fun `quotes fields containing newlines`() {
        assertEquals("\"a\nb\"", CsvWriter.escape("a\nb"))
        assertEquals("\"a\rb\"", CsvWriter.escape("a\rb"))
    }

    @Test
    fun `quotes fields with leading or trailing whitespace`() {
        assertEquals("\" padded \"", CsvWriter.escape(" padded "))
    }

    @Test
    fun `leaves plain fields unquoted`() {
        assertEquals("plain", CsvWriter.escape("plain"))
        assertEquals("", CsvWriter.escape(""))
    }

    @Test
    fun `preserves unicode without escaping`() {
        assertEquals("日本語🔐", CsvWriter.escape("日本語🔐"))
    }

    @Test
    fun `round trips through the parser`() {
        val values = listOf("plain", "a,b", "say \"hi\"", "line1\nline2", "", " spaced ", "日本語")
        val csv = CsvWriter.write(table(List(values.size) { "c$it" }, values))

        val parsed = DelimitedTextParser(delimiter = ',').parse(csv)

        assertEquals(2, parsed.size)
        assertEquals(values, parsed[1].fields)
    }

    @Test
    fun `header can be omitted`() {
        val csv = CsvWriter.write(
            table(listOf("a"), listOf("1")),
            CsvOptions(includeHeader = false),
        )
        assertEquals("1\r\n", csv)
    }

    @Test
    fun `byte order mark is written when requested`() {
        val csv = CsvWriter.write(table(listOf("a")), CsvOptions(writeByteOrderMark = true))
        assertTrue(csv.startsWith("﻿"))
    }

    @Test
    fun `streaming output matches the string output`() {
        val t = table(listOf("a", "b"), listOf("x,y", "日本語"), listOf("", "\"q\""))
        val out = ByteArrayOutputStream()
        CsvWriter.writeTo(t, out)

        assertEquals(CsvWriter.write(t), out.toString(StandardCharsets.UTF_8))
    }

    @Test
    fun `alternate delimiter is honoured`() {
        val csv = CsvWriter.write(table(listOf("a", "b"), listOf("1;2", "3")), CsvOptions(delimiter = ';'))
        assertEquals("a;b\r\n\"1;2\";3\r\n", csv)
    }
}
