package dev.herdr.android.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalInputTest {
    @Test
    fun terminalTypingPreservesUnicodeAndAppliesScreenModifiersOnce() {
        assertArrayEquals("echo café 🐚 中文".toByteArray(), TerminalInputEncoder.text("echo café 🐚 中文"))
        assertArrayEquals("\u0003afé".toByteArray(), TerminalInputEncoder.text("café", TerminalInputEncoder.Modifiers(ctrl = true)))
        assertArrayEquals("\u001Bx".toByteArray(), TerminalInputEncoder.text("x", TerminalInputEncoder.Modifiers(alt = true)))
        assertArrayEquals(byteArrayOf(27, 3), TerminalInputEncoder.text("c", TerminalInputEncoder.Modifiers(ctrl = true, alt = true)))
    }

    @Test
    fun controlSpaceAndControlPunctuationPreserveTheirLiteralBytes() {
        assertArrayEquals(byteArrayOf(0), TerminalInputEncoder.text(" ", TerminalInputEncoder.Modifiers(ctrl = true)))
        assertArrayEquals(byteArrayOf(0), TerminalInputEncoder.text("@", TerminalInputEncoder.Modifiers(ctrl = true)))
        assertArrayEquals(byteArrayOf(27), TerminalInputEncoder.text("[", TerminalInputEncoder.Modifiers(ctrl = true)))
        assertArrayEquals(byteArrayOf(127), TerminalInputEncoder.text("?", TerminalInputEncoder.Modifiers(ctrl = true)))
        assertNull(TerminalInputEncoder.text("🦉", TerminalInputEncoder.Modifiers(ctrl = true)))
    }

    @Test
    fun specialKeysHonorApplicationCursorAndXtermModifiers() {
        assertArrayEquals("\u001B[A".toByteArray(), TerminalInputEncoder.key(TerminalInputEncoder.Key.UP))
        assertArrayEquals("\u001BOA".toByteArray(), TerminalInputEncoder.key(TerminalInputEncoder.Key.UP, applicationCursor = true))
        assertArrayEquals("\u001B[1;5D".toByteArray(), TerminalInputEncoder.key(TerminalInputEncoder.Key.LEFT, TerminalInputEncoder.Modifiers(ctrl = true)))
        assertArrayEquals("\u001B[3;3~".toByteArray(), TerminalInputEncoder.key(TerminalInputEncoder.Key.DELETE, TerminalInputEncoder.Modifiers(alt = true)))
        assertArrayEquals("\u001B[Z".toByteArray(), TerminalInputEncoder.key(TerminalInputEncoder.Key.TAB, TerminalInputEncoder.Modifiers(shift = true)))
        assertArrayEquals("\u001B[24~".toByteArray(), TerminalInputEncoder.key(TerminalInputEncoder.Key.F12))
        assertArrayEquals("\r".toByteArray(), TerminalInputEncoder.key(TerminalInputEncoder.Key.ENTER))
        org.junit.Assert.assertEquals("ctrl+alt+shift+left", TerminalInputEncoder.wireName(TerminalInputEncoder.Key.LEFT, TerminalInputEncoder.Modifiers(ctrl = true, alt = true, shift = true)))
        org.junit.Assert.assertEquals("pageup", TerminalInputEncoder.wireName(TerminalInputEncoder.Key.PAGE_UP))
    }

    @Test
    fun modeParserHandlesFragmentedSequencesAndResetWithoutExecutingQueries() {
        val modes = TerminalInputModes()
        modes.observe("\u001B[?20".toByteArray())
        modes.observe("04;1h".toByteArray())
        assertTrue(modes.bracketedPaste)
        assertTrue(modes.applicationCursor)
        modes.observe("\u001B[6n\u001B[c\u001B[?1l".toByteArray())
        assertFalse(modes.applicationCursor)
        assertTrue(modes.bracketedPaste)
        modes.observe(byteArrayOf(), reset = true)
        assertFalse(modes.applicationCursor)
        assertFalse(modes.bracketedPaste)
    }

    @Test
    fun malformedSequencesAndControlStringPayloadsCannotChangeModes() {
        val modes = TerminalInputModes()
        modes.observe(("\u001B[?" + "0".repeat(100_000) + "1h").toByteArray())
        modes.observe("\u001B]52;c;\u001B[?1h\u0007".toByteArray())
        modes.observe("\u001BPpayload\u001B[?2004h\u001B\\".toByteArray())
        assertFalse(modes.applicationCursor)
        assertFalse(modes.bracketedPaste)
        modes.observe("\u001B[?1h".toByteArray())
        assertTrue(modes.applicationCursor)
    }

    @Test
    fun malformedUnicodeAndOversizedCompletionsAreRefused() {
        assertNull(TerminalInputEncoder.text("\uD800"))
        assertNull(TerminalInputEncoder.text("\uDC00"))
        assertNull(TerminalInputEncoder.text("x".repeat(8193)))
        assertArrayEquals(byteArrayOf(), TerminalInputEncoder.text(""))
    }
}
