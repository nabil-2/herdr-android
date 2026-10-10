package dev.herdr.android.data

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CodexConversationSelectionTest {
    private val id = "00000000-0000-4000-8000-000000000001"
    private val token = "a".repeat(43)
    private fun body(time: String = "100", title: String = "Synthetic chat", status: String = "working") =
        """{"conversations":[{"id":"$id","title":"$title","createdAt":$time,"status":"$status"}],"selectionToken":"$token"}"""

    @Test fun deliberateSelectionRequiresValidBoundedMetadataAndToken() {
        val choices = CodexConversationSelectionProtocol.decode(body())
        assertEquals(id, choices.conversations.single().id)
        assertEquals(100L, choices.conversations.single().createdAt)
        assertEquals("working", choices.conversations.single().status)
        assertTrue(CodexConversationSelectionProtocol.input(id, token).contains(token))
        listOf(body(time = "\"100\""), body(time = "-1"), body(status = "unsupported"), body(title = ""),
            body().replace(id, "../path"), body().replace(token, "expired"), body(title = "unsafe\\nlabel"))
            .forEach { assertThrows(IOException::class.java) { CodexConversationSelectionProtocol.decode(it) } }
    }

    @Test fun sharedPaneOpensChooserOnlyWithNegotiatedCapability() {
        val pane = Pane("w1:p1", "w1", "w1:t1", "Codex", "/tmp", "codex", conversation = ConversationAvailability("codex", false, selectable = true))
        assertTrue(ChatState.supportsPaneChat(pane, listOf("codex-chat", "codex-chat-selection")))
        assertFalse(ChatState.supportsPaneChat(pane, listOf("codex-chat")))
        assertFalse(ChatState.supportsPaneChat(pane.copy(conversation = pane.conversation!!.copy(provider = "claude")), listOf("codex-chat", "codex-chat-selection")))
    }

    @Test fun duplicateChoicesAndArbitrarySelectionInputsAreRejected() {
        val row = """{"id":"$id","title":"Synthetic","createdAt":100,"status":"idle"}"""
        assertThrows(IOException::class.java) { CodexConversationSelectionProtocol.decode("""{"conversations":[$row,$row],"selectionToken":"$token"}""") }
        assertThrows(IllegalArgumentException::class.java) { CodexConversationSelectionProtocol.input("another-session", token) }
        assertThrows(IllegalArgumentException::class.java) { CodexConversationSelectionProtocol.input(id, "wrong token") }
    }
}
