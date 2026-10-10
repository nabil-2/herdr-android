package dev.herdr.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class ConversationProtocolTest {
    private val page = """{"binding":"binding-1","revision":"revision-1","historyEpoch":"epoch-1","messages":[{"id":"m1","role":"assistant","text":"Hello","phase":"final_answer"}],"olderCursor":"older-1","unchanged":false,"canSend":true,"status":"idle","warning":null}"""

    @Test fun `canonical conversation identity is optional but must be a canonical UUID string`() {
        val id = "00000000-0000-4000-8000-000000000001"
        fun withIdentity(value: String) = page.dropLast(1) + ",\"conversationId\":$value}"
        assertEquals(id, ConversationProtocol.decode(withIdentity("\"$id\"")).conversationId)
        assertNull(ConversationProtocol.decode(page).conversationId)
        assertNull(ConversationProtocol.decode(withIdentity("null")).conversationId)
        listOf("1", "true", "{}", "[]", "\"\"", "\"1-1-1-1-1\"", "\"not-a-thread\"")
            .forEach { value -> assertThrows(IOException::class.java) { ConversationProtocol.decode(withIdentity(value)) } }
    }

    @Test fun `turn timestamps and readiness explanation remain optional and validated`() {
        val timed = page.replace("\"phase\":\"final_answer\"", "\"phase\":\"final_answer\",\"timestamp\":1791540060,\"timestampSource\":\"turn_end\"")
        val message = ConversationProtocol.decode(timed).messages.single()
        assertEquals(1791540060L, message.timestamp)
        assertEquals("turn_end", message.timestampSource)
        assertNull(ConversationProtocol.decode(page).messages.single().timestamp)
        val waiting = page.replace("\"canSend\":true", "\"canSend\":false,\"canSendReason\":\"Waiting for the agent.\"")
        assertEquals("Waiting for the agent.", ChatState.latest(ChatContent(), ConversationProtocol.decode(waiting)).canSendReason)
        listOf(
            timed.replace("1791540060", "\"1791540060\""),
            timed.replace("1791540060", "1791540060.5"),
            timed.replace("1791540060", "1791540060000"),
            timed.replace("1791540060", "-1"),
            timed.replace("1791540060", "null"),
            timed.replace("turn_end", "exact_message"),
        ).forEach { invalid -> assertThrows(IOException::class.java) { ConversationProtocol.decode(invalid) } }
    }

    @Test fun `message clocks remain separate from legacy turn times and reject invented clock values`() {
        val precise = page.replace("\"phase\":\"final_answer\"",
            "\"phase\":\"final_answer\",\"timestamp\":1791540000,\"timestampSource\":\"turn_start\",\"messageTimestamp\":1791540187")
        val message = ConversationProtocol.decode(precise).messages.single()
        assertEquals(1791540187L, message.messageTimestamp)
        assertEquals(1791540000L, message.timestamp)
        assertEquals("turn_start", message.timestampSource)
        assertNull(ConversationProtocol.decode(page).messages.single().messageTimestamp)
        assertNull(ConversationProtocol.decode(precise.replace("\"messageTimestamp\":1791540187", "\"messageTimestamp\":null"))
            .messages.single().messageTimestamp)
        listOf("\"1791540187\"", "1791540187.5", "1791540187000", "-1", "true", "{}", "[]")
            .forEach { value ->
                assertThrows(IOException::class.java) {
                    ConversationProtocol.decode(precise.replace("\"messageTimestamp\":1791540187", "\"messageTimestamp\":$value"))
                }
            }
    }

    @Test fun `message clocks survive refresh older page merging and rollback without adopting turn time`() {
        val precise = page.replace("\"phase\":\"final_answer\"",
            "\"phase\":\"final_answer\",\"timestamp\":1791540000,\"timestampSource\":\"turn_start\",\"messageTimestamp\":1791540187")
        val newest = ConversationProtocol.decode(precise)
        val current = ChatState.latest(ChatContent(), newest)
        val refreshed = ChatState.latest(current, newest)
        assertEquals(1791540187L, refreshed.messages.single().messageTimestamp)
        val older = ConversationProtocol.decode(precise.replace("\"m1\"", "\"m0\"")
            .replace("\"messageTimestamp\":1791540187", "\"messageTimestamp\":1791540071"))
        val merged = ChatState.older(refreshed, older)
        assertEquals(listOf(1791540071L, 1791540187L), merged.messages.map { it.messageTimestamp })
        val rolledBack = ConversationProtocol.decode(precise.replace("epoch-1", "epoch-2").replace("revision-1", "revision-2"))
        val remaining = ChatState.latest(merged, rolledBack)
        assertEquals(listOf("m1"), remaining.messages.map { it.id })
        assertEquals(1791540187L, remaining.messages.single().messageTimestamp)
        assertEquals(1791540000L, remaining.messages.single().timestamp)
    }

    @Test fun `delayed older history cannot overwrite newer agent readiness`() {
        val oldReadyPage = ConversationProtocol.decode(page)
        val current = ChatState.latest(ChatContent(), oldReadyPage).copy(
            canSend = false, status = "working", canSendReason = "Codex is working.",
        )
        val merged = ChatState.older(current, oldReadyPage)
        assertFalse(merged.canSend)
        assertEquals("working", merged.status)
        assertEquals("Codex is working.", merged.canSendReason)
    }

    @Test fun `only confirmed structured user and assistant messages are displayed`() {
        val decoded = ConversationProtocol.decode(page)
        assertEquals("Hello", decoded.messages.single().text)
        assertEquals("final_answer", decoded.messages.single().phase)
        listOf(
            page.replace("\"assistant\"", "\"system\""),
            page.replace("\"final_answer\"", "\"tool_output\""),
            page.replace("\"unchanged\":false", "\"unchanged\":\"false\""),
            page.replace("\"canSend\":true", "\"canSend\":\"true\""),
            page.replace("\"idle\"", "\"blocked\""),
            page.replace("\"idle\"", "\"unknown\""),
            page.replace("\"unchanged\":false", "\"unchanged\":true"),
        ).forEach { invalid -> assertThrows(IOException::class.java) { ConversationProtocol.decode(invalid) } }
    }

    @Test fun `authoritative readiness allows working followups while all delivery guards remain`() {
        val workingPage = ConversationProtocol.decode(page.replace("\"idle\"", "\"working\""))
        val working = ChatState.latest(ChatContent(), workingPage)
        org.junit.Assert.assertTrue(working.canSubmitMessage())
        listOf(
            working.copy(canSend = false), working.copy(bindingId = null),
            working.copy(loading = true), working.copy(sending = true),
            working.copy(deliveryUncertain = true), working.copy(error = "Check delivery"),
            working.copy(status = "blocked"), working.copy(status = "unknown"),
        ).forEach { assertFalse(it.canSubmitMessage()) }
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
