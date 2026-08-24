package com.jumbojuice.spasstocsv.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Converts the checked-in `samples/sample.spass`.
 *
 * That file was produced outside this project -- PBKDF2 from Python's `hashlib` and
 * AES-CBC from the `openssl` command line -- so decrypting it proves the implementation
 * interoperates with other tools rather than merely agreeing with itself.
 *
 * The sample holds invented credentials only.
 */
class SampleFileTest {

    private val sampleDirectory: File
        get() = File(System.getProperty("spass.sampleDir") ?: "../samples")

    private fun sample(): File = File(sampleDirectory, "sample.spass")

    @Test
    fun `decrypts and parses the checked-in sample export`() {
        val file = sample()
        assumeTrue(file.isFile, "samples/sample.spass is missing")

        val document = SpassConverter.convert(file.readBytes(), SAMPLE_PASSWORD.toCharArray())

        assertEquals(25, document.formatVersion)
        assertEquals(listOf(true, false, false, true), document.moduleFlags)
        assertEquals(listOf("passwords", "notes"), document.tables.map { it.name })

        val passwords = document.tables.first()
        assertEquals(SpassTestFixtures.PASSWORD_HEADERS, passwords.headers)
        assertEquals(4, passwords.rowCount)
        assertEquals("https://example.com/login", passwords.rows[0][1])
        assertEquals("correct horse battery staple", passwords.rows[0][7])

        // A password containing a double quote must survive decoding untouched.
        assertEquals("p@ss\"word", passwords.rows[1][7])

        // Unicode, including astral-plane characters.
        assertEquals("ユーザー", passwords.rows[2][4])
        assertEquals("パスワード🔐", passwords.rows[2][7])

        // Empty fields stay empty, and the NULL sentinel becomes empty too.
        assertEquals("", passwords.rows[3][4])
        assertEquals("", passwords.rows[0][32])

        val notes = document.tables[1]
        assertEquals(2, notes.rowCount)
        assertEquals("milk, eggs; bread\nand a second line", notes.rows[0][2])
        assertEquals("He said \"hello\" loudly", notes.rows[1][2])
    }

    @Test
    fun `the sample converts to csv that round trips`() {
        val file = sample()
        assumeTrue(file.isFile, "samples/sample.spass is missing")

        val document = SpassConverter.convert(file.readBytes(), SAMPLE_PASSWORD.toCharArray())
        val notes = document.tables[1]
        val csv = SpassConverter.toCsv(notes)

        // Values with a comma, a semicolon, a newline or a quote must come back identical.
        val reparsed = DelimitedTextParser(delimiter = ',').parse(csv)
        assertEquals(notes.headers, reparsed[0].fields)
        assertEquals(notes.rows[0], reparsed[1].fields)
        assertEquals(notes.rows[1], reparsed[2].fields)
        assertTrue(csv.contains("\"milk, eggs; bread\nand a second line\""))
    }

    @Test
    fun `the wrong password on the sample is reported cleanly`() {
        val file = sample()
        assumeTrue(file.isFile, "samples/sample.spass is missing")

        val error = org.junit.jupiter.api.assertThrows<SpassException.WrongPasswordOrCorrupt> {
            SpassConverter.convert(file.readBytes(), "not-the-password".toCharArray())
        }
        assertTrue(error.message!!.contains("password", ignoreCase = true))
    }

    private companion object {
        const val SAMPLE_PASSWORD = "sample-password"
    }
}
