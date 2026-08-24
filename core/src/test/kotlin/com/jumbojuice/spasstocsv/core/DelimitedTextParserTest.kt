package com.jumbojuice.spasstocsv.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DelimitedTextParserTest {

    private fun parse(text: String, delimiter: Char = ';') =
        DelimitedTextParser(delimiter = delimiter).parse(text).map { it.fields }

    @Test
    fun `splits simple rows`() {
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), parse("a;b;c\n1;2;3"))
    }

    @Test
    fun `handles empty input`() {
        assertEquals(emptyList<List<String>>(), parse(""))
    }

    @Test
    fun `keeps empty fields`() {
        assertEquals(listOf(listOf("a", "", "c", "")), parse("a;;c;"))
    }

    @Test
    fun `handles all three line endings including mixed`() {
        val expected = listOf(listOf("a"), listOf("b"), listOf("c"), listOf("d"))
        assertEquals(expected, parse("a\nb\r\nc\rd"))
    }

    @Test
    fun `preserves delimiter inside quoted value`() {
        assertEquals(listOf(listOf("a;b", "c")), parse("\"a;b\";c"))
    }

    @Test
    fun `unescapes doubled quotes`() {
        assertEquals(listOf(listOf("say \"hi\"", "x")), parse("\"say \"\"hi\"\"\";x"))
    }

    @Test
    fun `preserves newline inside quoted value`() {
        assertEquals(listOf(listOf("line1\nline2", "b")), parse("\"line1\nline2\";b"))
    }

    @Test
    fun `normalises CRLF inside quoted value`() {
        assertEquals(listOf(listOf("line1\nline2")), parse("\"line1\r\nline2\""))
    }

    @Test
    fun `preserves unicode`() {
        assertEquals(listOf(listOf("日本語", "emoji 🔐", "café")), parse("日本語;emoji 🔐;café"))
    }

    @Test
    fun `keeps a stray quote inside an unquoted field`() {
        assertEquals(listOf(listOf("a\"b", "c")), parse("a\"b;c"))
    }

    @Test
    fun `unterminated quote is reported, not thrown`() {
        val parser = DelimitedTextParser(delimiter = ';')
        val records = parser.parse("a;\"unterminated\nstill going")

        assertEquals(1, records.size)
        assertEquals(listOf("a", "unterminated\nstill going"), records[0].fields)
        assertTrue(parser.warnings.any { it.kind == ConversionWarning.Kind.UNTERMINATED_QUOTE })
    }

    @Test
    fun `blank lines are skipped`() {
        assertEquals(listOf(listOf("a"), listOf("b")), parse("a\n\n\nb\n"))
    }

    @Test
    fun `reports the line each record started on`() {
        val records = DelimitedTextParser().parse("a\nb\n\"multi\nline\"\nd", firstLineNumber = 5)
        assertEquals(listOf(5, 6, 7, 9), records.map { it.startLine })
    }

    @Test
    fun `supports comma and tab delimiters`() {
        assertEquals(listOf(listOf("a", "b")), parse("a,b", delimiter = ','))
        assertEquals(listOf(listOf("a", "b")), parse("a\tb", delimiter = '\t'))
    }
}
