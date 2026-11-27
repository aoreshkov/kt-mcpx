package app.oreshkov.ktmcpx.stdio

import kotlinx.io.Buffer
import kotlinx.io.Source
import kotlinx.io.readLine
import kotlinx.io.writeString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BoundedLinesTest {
    private fun source(text: String): Source = Buffer().apply { writeString(text) }

    private fun Source.lines(max: Long): List<String> = generateSequence { readBoundedLine(max) }
        .map { if (it is InputLine.Text) it.text else "<too long>" }
        .toList()

    @Test
    fun `valid lines read as readLine reads them`() {
        val text = "first\r\nsecond\n\nthird\r\n\rx\nlast without newline"
        val expected = source(text).let { s -> generateSequence { s.readLine() }.toList() }

        assertEquals(expected, source(text).lines(max = 64))
    }

    @Test
    fun `a line of exactly the limit is read, one byte more is not`() {
        assertEquals(listOf("abcd", "ok"), source("abcd\nok\n").lines(max = 4))
        assertEquals(listOf("ab\r", "ok"), source("ab\r\r\nok\n").lines(max = 4))
        assertEquals(listOf("<too long>", "ok"), source("abcde\nok\n").lines(max = 4))
    }

    @Test
    fun `a long line is skipped to its newline across many chunks`() {
        val long = "x".repeat(300_000)
        assertEquals(listOf("a", "<too long>", "b"), source("a\n$long\nb\n").lines(max = 1024))
    }

    @Test
    fun `a long last line without a newline ends the input`() {
        val src = source("a\n" + "y".repeat(5000))
        assertEquals(listOf("a", "<too long>"), src.lines(max = 100))
        assertTrue(src.exhausted())
    }

    @Test
    fun `an empty source has no line, and a short last line is returned`() {
        assertNull(source("").readBoundedLine(10))
        assertIs<InputLine.Text>(source("tail").readBoundedLine(10)).also { assertEquals("tail", it.text) }
    }
}
