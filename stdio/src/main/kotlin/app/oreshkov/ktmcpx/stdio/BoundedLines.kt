package app.oreshkov.ktmcpx.stdio

import kotlinx.io.Source
import kotlinx.io.indexOf
import kotlinx.io.readByteArray
import kotlinx.io.readString

/** One line of input, as [readBoundedLine] reads it. */
internal sealed interface InputLine {
    /** A line without its `\n` or `\r\n` terminator. */
    class Text(val text: String) : InputLine

    /** A line longer than the limit. It was skipped up to and including its newline. */
    data object TooLong : InputLine
}

private const val NEWLINE = '\n'.code.toByte()
private const val SKIP_CHUNK = 64L * 1024

/**
 * Reads the next line of at most [maxLength] bytes, not counting its `\n`, or `null` at the end of the
 * source. A valid line reads as `readLine` would read it: without `\n` or `\r\n`, and the last line may
 * end without a newline. A longer line is never held in full: once [maxLength] bytes have arrived
 * with no newline, the rest is skipped chunk by chunk up to the next newline, and [InputLine.TooLong] is
 * returned, so memory stays bounded by the limit.
 */
internal fun Source.readBoundedLine(maxLength: Long): InputLine? {
    val newline = indexOf(NEWLINE, 0, maxLength + 1)
    if (newline >= 0) {
        val line = readString(newline)
        skip(1)
        return InputLine.Text(line.removeSuffix("\r"))
    }
    if (!request(maxLength + 1)) {
        // The source ended within the limit: what is left is the last line, if anything.
        if (exhausted()) return null
        return InputLine.Text(readString())
    }
    // indexOf scanned these bytes: none is a newline.
    skip(maxLength + 1)
    while (true) {
        val next = indexOf(NEWLINE, 0, SKIP_CHUNK)
        if (next >= 0) {
            skip(next + 1)
            return InputLine.TooLong
        }
        if (request(SKIP_CHUNK)) {
            skip(SKIP_CHUNK)
        } else {
            readByteArray()
            return InputLine.TooLong
        }
    }
}
