package dev.herdr.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCommandMemoryTest {
    @Test fun historyIsOptInBoundedAndDeletedWhenDisabled() {
        val memory = TerminalCommandMemory()
        memory.acknowledgeComposedCommand("secret before opt-in")
        assertTrue(memory.history.isEmpty())
        memory.rememberCommands = true
        repeat(25) { memory.acknowledgeComposedCommand("command-$it") }
        assertEquals(20, memory.history.size)
        memory.acknowledgeComposedCommand("command-24")
        assertEquals(20, memory.history.size)
        assertEquals("command-24", memory.history.first())
        memory.rememberCommands = false
        assertTrue(memory.history.isEmpty())
    }

    @Test fun snippetsRequireExplicitSaveAreBoundedAndCanBeDeleted() {
        val memory = TerminalCommandMemory()
        assertFalse(memory.saveSnippet("", "echo hello"))
        assertFalse(memory.saveSnippet("Empty", " "))
        assertFalse(memory.saveSnippet("Oversize", "x".repeat(8193)))
        repeat(20) { assertTrue(memory.saveSnippet("command-$it", "echo $it")) }
        assertFalse(memory.saveSnippet("Overflow", "echo no"))
        val saved = memory.snippets.first()
        assertFalse(saved.toString().contains(saved.text))
        memory.deleteSnippet(saved.id)
        assertEquals(19, memory.snippets.size)
        assertTrue(memory.saveSnippet("New", "echo new"))
    }

    @Test fun commandHistoryNeverReusesAnIncompleteLargeCommandOrSplitsUnicode() {
        val memory = TerminalCommandMemory().apply { rememberCommands = true }
        val exact = "echo " + "a".repeat(8100) + "😀"
        memory.acknowledgeComposedCommand(exact)
        memory.acknowledgeComposedCommand("a".repeat(8191) + "😀")
        memory.acknowledgeComposedCommand("x".repeat(16000))
        assertEquals(listOf(exact), memory.history)
    }

    @Test fun pasteRejectsTerminalControlsAndOversizedTextButAllowsComposeNewlines() {
        assertTrue(isSafeTerminalPaste("echo hello"))
        assertTrue(isSafeTerminalPaste("printf 'hello'\n\techo next"))
        assertFalse(isSafeTerminalPaste("\u001B[200~rm -rf"))
        assertFalse(isSafeTerminalPaste("\u0000"))
        assertFalse(isSafeTerminalPaste("echo\u007Fexecute"))
        assertFalse(isSafeTerminalPaste("\u009B31m"))
        assertFalse(isSafeTerminalPaste("x".repeat(16385)))
        assertFalse(isSafeTerminalPaste("😀".repeat(8193)))
    }

    @Test fun shortcutButtonsRespectApplicationCursorAndOneShotModifiers() {
        val modes = TerminalInputModes()
        modes.observe("\u001B[?1h".toByteArray())
        assertEquals("\u001BOA", terminalShortcutBytes("up", false, false, modes)!!.toString(Charsets.UTF_8))
        assertEquals("\u001B[1;5D", terminalShortcutBytes("left", true, false, modes)!!.toString(Charsets.UTF_8))
        assertEquals("\u001B\u0003", terminalShortcutBytes("ctrl+c", false, true, modes)!!.toString(Charsets.UTF_8))
        assertEquals("\u001B[Z", terminalShortcutBytes("shift+tab", false, false, modes)!!.toString(Charsets.UTF_8))
        assertEquals(null, terminalShortcutBytes("unsupported", false, false, modes))
    }
}
