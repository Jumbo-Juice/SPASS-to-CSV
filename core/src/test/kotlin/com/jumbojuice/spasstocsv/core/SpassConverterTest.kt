package com.jumbojuice.spasstocsv.core

import com.jumbojuice.spasstocsv.core.SpassTestFixtures.NOTE_HEADERS
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.nio.charset.StandardCharsets

/** End-to-end: encrypted file in, CSV text out. */
class SpassConverterTest {

    @Test
    fun `converts an encrypted export all the way to csv`() {
        val file = SpassTestFixtures.encrypt(SpassTestFixtures.simplePasswordPayload(), "pw")

        val document = SpassConverter.convert(file, "pw".toCharArray())
        val csv = SpassConverter.toCsv(document.tables.single())

        assertTrue(csv.startsWith("id,origin_url,"))
        assertTrue(csv.contains("https://example.com/login"))
        assertTrue(csv.contains("alice@example.com"))
        assertTrue(csv.contains("hunter2"))
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun `csv keeps commas quotes newlines and unicode intact`() {
        val payload = SpassTestFixtures.buildPayload(
            modules = listOf(false, false, false, true),
            tables = listOf(
                NOTE_HEADERS to listOf(
                    listOf("1", "a,b", "he said \"hi\"", "0"),
                    listOf("2", "line1\nline2", "日本語 🔐", "0"),
                )
            ),
        )
        val file = SpassTestFixtures.encrypt(payload, "pw")

        val document = SpassConverter.convert(file, "pw".toCharArray())
        val csv = SpassConverter.toCsv(document.tables.single())

        // Re-parse the CSV we produced and check every value survived the round trip.
        val reparsed = DelimitedTextParser(delimiter = ',').parse(csv)
        assertEquals(NOTE_HEADERS, reparsed[0].fields)
        assertEquals(listOf("1", "a,b", "he said \"hi\"", "0"), reparsed[1].fields)
        assertEquals(listOf("2", "line1\nline2", "日本語 🔐", "0"), reparsed[2].fields)
    }

    @Test
    fun `an already decrypted payload needs no password`() {
        val plaintext = SpassTestFixtures.simplePasswordPayload().toByteArray(StandardCharsets.UTF_8)

        assertFalse(SpassConverter.requiresPassword(plaintext))
        assertEquals(1, SpassConverter.convert(plaintext).totalRows)
    }

    @Test
    fun `an encrypted file is reported as needing a password`() {
        val file = SpassTestFixtures.encrypt(SpassTestFixtures.simplePasswordPayload(), "pw")

        assertTrue(SpassConverter.requiresPassword(file))
        val error = assertThrows<SpassException.WrongPasswordOrCorrupt> {
            SpassConverter.convert(file, null)
        }
        assertTrue(error.message!!.contains("password", ignoreCase = true))
    }

    @Test
    fun `empty file gives a specific error`() {
        val error = assertThrows<SpassException.UnsupportedFileFormat> {
            SpassConverter.convert(ByteArray(0), "pw".toCharArray())
        }
        assertTrue(error.message!!.contains("empty", ignoreCase = true))
    }

    @Test
    fun `a random binary file is rejected without crashing`() {
        val junk = ByteArray(5000) { (it * 31 % 256 - 128).toByte() }

        assertThrows<SpassException> { SpassConverter.convert(junk, "pw".toCharArray()) }
    }

    @Test
    fun `a text file that is not an export is rejected without crashing`() {
        val notAnExport = "hello world, this is just a note to self.\n".repeat(20)
            .toByteArray(StandardCharsets.UTF_8)

        assertThrows<SpassException> { SpassConverter.convert(notAnExport, "pw".toCharArray()) }
    }

    @Test
    fun `suggested csv name is derived from the source file`() {
        assertEquals("MyVault-passwords.csv", SpassConverter.suggestCsvName("MyVault.spass", "passwords"))
        assertEquals("spass-export-notes.csv", SpassConverter.suggestCsvName(null, "notes"))
        // Directory components are dropped ...
        assertEquals("name-cards.csv", SpassConverter.suggestCsvName("odd/name.spass", "cards"))
        // ... and characters that are awkward in a file name are replaced.
        assertEquals("we_ird_na_me-cards.csv", SpassConverter.suggestCsvName("we:ird*na?me.spass", "cards"))
    }

    @Test
    fun `a large encrypted export converts end to end`() {
        val rows = (1..5_000).map { listOf(it.toString(), "Note $it", "Body $it, with a comma", "0") }
        val payload = SpassTestFixtures.buildPayload(
            modules = listOf(false, false, false, true),
            tables = listOf(NOTE_HEADERS to rows),
        )
        val file = SpassTestFixtures.encrypt(payload, "pw")

        val document = SpassConverter.convert(file, "pw".toCharArray())
        val csv = SpassConverter.toCsv(document.tables.single())

        assertEquals(5_000, document.totalRows)
        assertEquals(5_001, csv.trimEnd('\r', '\n').split("\r\n").size)
        assertTrue(csv.contains("\"Body 5000, with a comma\""))
    }

    @Test
    fun `oversized input is refused before allocating`() {
        // The guard is on length only, so a sparse array is enough to exercise it.
        val huge = ByteArray((SpassConverter.MAX_INPUT_BYTES + 1).toInt())
        assertThrows<SpassException.UnableToRead> { SpassConverter.convert(huge, "pw".toCharArray()) }
    }
}
