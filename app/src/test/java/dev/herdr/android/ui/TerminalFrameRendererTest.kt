package dev.herdr.android.ui

import dev.herdr.android.data.TerminalFrame
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFrameRendererTest {
    @Test fun `complete redraw is one write and identical snapshots do not clear the screen again`() {
        val writes = mutableListOf<ByteArray>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        val renderer = TerminalFrameRenderer(30, 80, writes::add, { rows, cols -> sizes += rows to cols })
        val frame = TerminalFrame("\u001B[2J\u001B[HReadable output".toByteArray(), 80, 30, true, 1, "w1:p1")
        assertTrue(renderer.render(frame))
        assertEquals(1, writes.size)
        assertArrayEquals("\u001Bc\u001B[2J\u001B[HReadable output".toByteArray(), writes.single())
        assertFalse(renderer.render(frame.copy(ansi = frame.ansi.copyOf())))
        assertEquals(1, writes.size)
        assertTrue(sizes.isEmpty())
        assertEquals(1L, renderer.sessionId)
    }

    @Test fun `delta and a new observer session invalidate the snapshot cache without replaying input`() {
        val writes = mutableListOf<ByteArray>()
        val sizes = mutableListOf<Pair<Int, Int>>()
        val renderer = TerminalFrameRenderer(30, 80, writes::add, { rows, cols -> sizes += rows to cols })
        val frame = TerminalFrame("\u001B[HOld screen".toByteArray(), 80, 30, true, 1, "w1:p1")
        renderer.render(frame)
        val delta = frame.copy(ansi = "\u001B[2;1HNew line".toByteArray(), reset = false)
        assertTrue(renderer.render(delta))
        assertArrayEquals(delta.ansi, writes.last())
        assertTrue(renderer.render(frame))
        assertTrue(renderer.render(frame.copy(sessionId = 2, rows = 40, cols = 100)))
        assertEquals(listOf(40 to 100), sizes)
        assertEquals(4, writes.size)
        assertArrayEquals(byteArrayOf(27, 'c'.code.toByte()) + frame.ansi, writes.last())
        assertEquals(2L, renderer.sessionId)
    }

    @Test fun `first delta from a new session resets and paints in the same write`() {
        val writes = mutableListOf<ByteArray>()
        val renderer = TerminalFrameRenderer(30, 80, writes::add, { _, _ -> })
        val frame = TerminalFrame("A new observer".toByteArray(), 80, 30, false, 3, "w1:p1")
        assertTrue(renderer.render(frame))
        assertEquals(1, writes.size)
        assertArrayEquals("\u001BcA new observer".toByteArray(), writes.single())
    }
}
