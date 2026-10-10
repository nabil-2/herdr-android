package dev.herdr.android.data

import org.junit.Assert.*
import org.junit.Test

class ChatHistoryStoreTest {
    private fun content(binding: String = "original") = ChatContent(
        bindingId = binding, conversationId = "00000000-0000-4000-8000-000000000001",
        revision = "revision", messages = listOf(ChatMessage("answer", "assistant", "Original synthetic answer")),
        draft = "private unsent draft", canSend = true, sending = true,
        attachments = listOf(ChatAttachment("local", "image.png", ChatAttachmentKind.IMAGE, remoteId = "receipt", previewPath = "/private/preview")),
    )

    @Test fun `histories are scoped to profile and pane and never restore live input or attachments`() {
        val store = ChatHistoryStore()
        store.remember("profile", "pane-one", content())
        store.remember("profile", "pane-two", content("another"))
        val restored = store.restore("profile", "pane-one")!!
        assertEquals(content().messages, restored.messages)
        assertEquals("original", restored.bindingId)
        assertEquals("", restored.draft)
        assertFalse(restored.canSend)
        assertFalse(restored.sending)
        assertTrue(restored.attachments.isEmpty())
        assertEquals("another", store.restore("profile", "pane-two")!!.bindingId)
        assertNull(store.restore("another-profile", "pane-one"))
        assertNull(store.restore("profile", "another-pane"))
        store.remove("profile", "pane-one")
        assertNull(store.restore("profile", "pane-one"))
        assertNotNull(store.restore("profile", "pane-two"))
    }

    @Test fun `oversized histories keep their identity and bounded recent messages for rebind protection`() {
        val store = ChatHistoryStore(maxCharacters = 160)
        val original = content().copy(messages = listOf(ChatMessage("large", "assistant", "x".repeat(1000)), ChatMessage("recent", "assistant", "Last original answer")))
        store.remember("profile", "pane", original)
        val restored = store.restore("profile", "pane")!!
        assertEquals(original.conversationId, restored.conversationId)
        assertEquals(original.bindingId, restored.bindingId)
        assertEquals(listOf("recent"), restored.messages.map { it.id })
        assertNull(restored.revision)
        val foreign = ConversationPage("new", "r", "e", emptyList(), unchanged = false, canSend = true, status = "idle",
            conversationId = "00000000-0000-4000-8000-000000000002")
        assertTrue(ChatState.changesConversation(restored, foreign))
    }

    @Test fun `bounded cache evicts least recently read entries and profile removal clears only that profile`() {
        val store = ChatHistoryStore(maxEntries = 2)
        store.remember("profile", "one", content())
        store.remember("profile", "two", content())
        store.restore("profile", "one")
        store.remember("other-profile", "three", content())
        assertNull(store.restore("profile", "two"))
        assertNotNull(store.restore("profile", "one"))
        store.removeProfile("profile")
        assertNull(store.restore("profile", "one"))
        assertNotNull(store.restore("other-profile", "three"))
    }
}
