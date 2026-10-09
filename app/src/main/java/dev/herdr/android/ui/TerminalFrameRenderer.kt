package dev.herdr.android.ui

import dev.herdr.android.data.TerminalFrame

/** Apply one observer frame as one native write, so a redraw cannot publish its empty reset first. */
internal class TerminalFrameRenderer(
    initialRows: Int,
    initialCols: Int,
    private val write: (ByteArray) -> Unit,
    private val resize: (Int, Int) -> Unit,
) {
    var sessionId: Long = -1L
        private set
    private var rows = initialRows
    private var cols = initialCols
    private var previousFull: ByteArray? = null

    fun render(frame: TerminalFrame): Boolean {
        val newSession = sessionId != frame.sessionId
        val newSize = rows != frame.rows || cols != frame.cols
        if (!newSession && !newSize && frame.reset && previousFull?.contentEquals(frame.ansi) == true) return false
        if (newSize) {
            resize(frame.rows, frame.cols)
            rows = frame.rows
            cols = frame.cols
        }
        // libvterm holds its damage lock for a complete write. Keep reset and paint
        // under that same lock rather than exposing a blank snapshot between them.
        val bytes = if (frame.reset || newSession) byteArrayOf(27, 'c'.code.toByte()) + frame.ansi else frame.ansi
        write(bytes)
        sessionId = frame.sessionId
        previousFull = if (frame.reset) frame.ansi.copyOf() else null
        return true
    }
}
