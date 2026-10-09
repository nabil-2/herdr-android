package dev.herdr.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ConversationProtocolTest {
    private val page = """{"binding":"binding-1","revision":"revision-1","historyEpoch":"epoch-1","messages":[{"id":"m1","role":"assistant","text":"Hello","phase":"final_answer"}],"olderCursor":"older-1","unchanged":false,"canSend":true,"status":"idle","warning":null}"""

    @Test fun `only confirmed structured user and assistant messages are displayed`() {
        val decoded = ConversationProtocol.decode(page)
        assertEquals("Hello", decoded.messages.single().text)
        assertEquals("final_answer", decoded.messages.single().phase)
        listOf(
            page.replace("\"assistant\"", "\"system\""),
            page.replace("\"final_answer\"", "\"tool_output\""),
            page.replace("\"unchanged\":false", "\"unchanged\":\"false\""),
            page.replace("\"canSend\":true", "\"canSend\":\"true\""),
            page.replace("\"idle\"", "\"working\""),
            page.replace("\"unchanged\":false", "\"unchanged\":true"),
        ).forEach { invalid -> assertThrows(IOException::class.java) { ConversationProtocol.decode(invalid) } }
    }

    @Test fun `duplicate stable IDs and oversized pages are rejected`() {
        val message = """{"id":"m1","role":"user","text":"hello"}"""
        val duplicate = page.replace("""[{"id":"m1","role":"assistant","text":"Hello","phase":"final_answer"}]""", "[$message,$message]")
        assertThrows(IOException::class.java) { ConversationProtocol.decode(duplicate) }
        val large = page.replace("""[{"id":"m1","role":"assistant","text":"Hello","phase":"final_answer"}]""",
            (1..51).joinToString(prefix = "[", postfix = "]") { """{"id":"m$it","role":"user","text":"hello"}""" })
        assertThrows(IOException::class.java) { ConversationProtocol.decode(large) }
    }

    @Test fun `chat input preserves multiline text without terminal escape or key interpretation`() {
        assertEquals("""{"binding":"binding-1","text":"line one\nline two"}""", ConversationProtocol.input("binding-1", "line one\nline two"))
        assertThrows(IllegalArgumentException::class.java) { ConversationProtocol.input("binding-1", " ") }
        assertThrows(IllegalArgumentException::class.java) { ConversationProtocol.input("binding-1", "bad\u0000text") }
        assertThrows(IllegalArgumentException::class.java) { ConversationProtocol.input("binding-1", "bad\u001b[31mtext") }
        assertThrows(IllegalArgumentException::class.java) { ConversationProtocol.input("binding-1", "😀".repeat(8193)) }
    }

    @Test fun `only boolean success acknowledgement permits clearing a draft`() {
        assertNull(ConversationProtocol.acknowledgement("""{"ok":true,"warning":null}"""))
        assertEquals("Formatting changed", ConversationProtocol.acknowledgement("""{"ok":true,"warning":"Formatting changed"}"""))
        listOf("""{"ok":"true"}""", """{"ok":false}""", """{"ok":true,"warning":12}""", "not json").forEach {
            assertThrows(IOException::class.java) { ConversationProtocol.acknowledgement(it) }
        }
    }

    @Test fun `chat state string representation excludes private draft and messages`() {
        val state = ChatContent(draft = "private pending message", messages = listOf(ChatMessage("m1", "user", "private history")))
        assertFalse(state.toString().contains("private"))
    }
}
