package dev.herdr.android.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalProtocolTest {
    @Test fun `actual Herdr full and delta frames preserve ANSI bytes`() {
        val full = TerminalProtocol.decode("""{"bytes":"G1sySkhlbGxv","encoding":"ansi","full":true,"height":30,"seq":1,"type":"terminal.frame","width":80}""") as TerminalMessage.Frame
        assertArrayEquals("\u001b[2JHello".toByteArray(), full.ansi)
        assertTrue(full.full)
        assertEquals(80, full.cols)
        assertEquals(30, full.rows)
        val delta = TerminalProtocol.decode("""{"bytes":"4piD","encoding":"ansi","full":false,"height":30,"seq":2,"type":"terminal.frame","width":80}""") as TerminalMessage.Frame
        assertArrayEquals("☃".toByteArray(), delta.ansi)
        assertFalse(delta.full)
    }

    @Test fun `closed session carries the actual CLI reason`() {
        val closed = TerminalProtocol.decode("""{"reason":"terminal term_test exited","type":"terminal.closed"}""") as TerminalMessage.Closed
        assertEquals("terminal term_test exited", closed.reason)
    }

    @Test fun `corrupt and incompatible frames fail instead of losing terminal state`() {
        listOf(
            """{"type":"terminal.frame","bytes":"bad base64","encoding":"ansi","full":false,"width":80,"height":30}""",
            """{"type":"terminal.frame","bytes":"","encoding":"html","full":true,"width":80,"height":30}""",
            """{"type":"terminal.frame","bytes":"","encoding":"ansi","full":true,"width":5000,"height":30}""",
            """{"type":"terminal.frame","bytes":"","encoding":"ansi","width":80,"height":30}""",
            """{"type":"arbitrary"}""",
        ).forEach { assertThrows(Exception::class.java) { TerminalProtocol.decode(it) } }
    }
}
