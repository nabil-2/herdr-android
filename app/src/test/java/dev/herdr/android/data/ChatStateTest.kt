package dev.herdr.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatStateTest {
    private fun message(id: Int) = ChatMessage("m$id", if (id % 2 == 0) "assistant" else "user", "message $id")
    private fun page(ids: IntRange, revision: String = "r1", epoch: String = "e1", binding: String = "b1", cursor: String? = "older") =
        ConversationPage(binding, revision, epoch, ids.map(::message), cursor, false, true, "idle")

    @Test fun `native chat defaults require both capability and positively confirmed Codex binding`() {
        val pane = Pane("w1:p1", "w1", "w1:t1", "Codex", "/project", agent = "codex")
        assertFalse(ChatState.supportsPaneChat(pane, listOf("codex-chat")))
        val confirmed = pane.copy(conversation = ConversationAvailability("codex", true))
        assertTrue(ChatState.supportsPaneChat(confirmed, listOf("codex-chat")))
        assertFalse(ChatState.supportsPaneChat(confirmed, emptyList()))
        assertFalse(ChatState.supportsPaneChat(confirmed.copy(conversation = ConversationAvailability("codex", false, "Unavailable")), listOf("codex-chat")))
        assertFalse(ChatState.supportsPaneChat(confirmed.copy(conversation = ConversationAvailability("claude", true)), listOf("codex-chat")))
    }

    @Test fun `latest updates preserve loaded history and replace matching stable IDs`() {
        val initial = ChatState.latest(ChatContent(), page(51..100)).copy(draft = "pending")
        val older = ChatState.older(initial, page(1..50, cursor = null))
        val updated = ChatState.latest(older, page(52..101, revision = "r2"))
        assertEquals((1..101).map { "m$it" }, updated.messages.map { it.id })
        assertEquals("pending", updated.draft)
        assertEquals("older", updated.olderCursor)
        assertEquals(101, updated.messages.map { it.id }.distinct().size)
    }

    @Test fun `unchanged polls preserve pagination and reflect current readiness`() {
        val initial = ChatState.latest(ChatContent(), page(51..100))
        val older = ChatState.older(initial, page(1..50, cursor = "oldest"))
        val unchanged = page(IntRange.EMPTY).copy(unchanged = true, canSend = false, status = "blocked")
        val updated = ChatState.latest(older, unchanged)
        assertEquals(older.messages, updated.messages)
        assertEquals("oldest", updated.olderCursor)
        assertFalse(updated.canSend)
        assertEquals("blocked", updated.status)
    }

    @Test fun `rollback discards removed history while retaining binding scoped draft`() {
        val initial = ChatState.latest(ChatContent(), page(1..50)).copy(draft = "my draft")
        val rollback = ChatState.latest(initial, page(1..5, revision = "r2", epoch = "e2", cursor = null))
        assertEquals((1..5).map { "m$it" }, rollback.messages.map { it.id })
        assertEquals("my draft", rollback.draft)
    }

    @Test fun `legacy binding changes preserve original history and reject late acknowledgement`() {
        val initial = ChatState.latest(ChatContent(), page(1..5)).copy(draft = "old draft", sending = true)
        val rebound = ChatState.latest(initial, page(6..10, binding = "b2", epoch = "e2"))
        assertEquals("old draft", rebound.draft)
        assertEquals(initial.messages, rebound.messages)
        assertTrue(rebound.conversationChanged)
        assertTrue(rebound.deliveryUncertain)
        assertFalse(rebound.canSend)
        assertFalse(rebound.sending)
        val edited = rebound.copy(draft = "new thread draft")
        assertEquals(edited, ChatState.acknowledged(edited, "b1", "old draft", null))
    }

    @Test fun `same canonical conversation survives binding rotation without dropping draft or delivery guards`() {
        val id = "00000000-0000-4000-8000-000000000001"
        val initial = ChatState.latest(ChatContent(), page(1..5).copy(conversationId = id)).copy(
            draft = "reviewed original draft", sending = true,
            attachments = listOf(ChatAttachment("local", "image.png", ChatAttachmentKind.IMAGE, remoteId = "receipt", status = ChatAttachmentStatus.READY)),
        )
        val rotated = ChatState.latest(initial, page(1..6, binding = "rotated-binding", epoch = "new-cache").copy(conversationId = id))
        assertFalse(rotated.conversationChanged)
        assertEquals(id, rotated.conversationId)
        assertEquals("rotated-binding", rotated.bindingId)
        assertEquals("reviewed original draft", rotated.draft)
        assertTrue(rotated.deliveryUncertain)
        assertTrue(rotated.attachmentDeliveryUncertain)
        assertFalse(rotated.sending)
        assertTrue(rotated.attachments.isEmpty())
        assertFalse(rotated.canSubmitMessage())
        assertEquals(rotated, ChatState.acknowledged(rotated, "b1", initial.draft, null))
    }

    @Test fun `changed canonical ID cannot substitute another history even if binding revision and epoch match`() {
        val original = ChatState.latest(ChatContent(), page(1..5).copy(conversationId = "00000000-0000-4000-8000-000000000001"))
        val incorrect = page(6..10).copy(conversationId = "00000000-0000-4000-8000-000000000002")
        val guarded = ChatState.latest(original, incorrect)
        assertEquals(original.messages, guarded.messages)
        assertEquals(original.conversationId, guarded.conversationId)
        assertTrue(guarded.conversationChanged)
        assertFalse(guarded.canSubmitMessage())
        assertThrows(IllegalArgumentException::class.java) { ChatState.older(original, incorrect) }
    }

    @Test fun `successful send clears only the exact submitted text`() {
        val initial = ChatState.latest(ChatContent(), page(1..5)).copy(draft = "first", sending = true)
        val acknowledged = ChatState.acknowledged(initial, "b1", "first", null)
        assertEquals("", acknowledged.draft)
        assertEquals(1L, acknowledged.ackRevision)
        assertFalse(acknowledged.canSend)
        val edited = ChatState.acknowledged(initial.copy(draft = "edited while sending"), "b1", "first", null)
        assertEquals("edited while sending", edited.draft)
        assertEquals(1L, edited.ackRevision)
    }

    @Test fun `old pagination responses cannot restore another revision or conversation`() {
        val initial = ChatState.latest(ChatContent(), page(51..100))
        assertThrows(IllegalArgumentException::class.java) { ChatState.older(initial, page(1..50, revision = "r2")) }
        assertThrows(IllegalArgumentException::class.java) { ChatState.older(initial, page(1..50, binding = "b2")) }
        assertThrows(IllegalArgumentException::class.java) { ChatState.older(initial, page(1..50, epoch = "e2")) }
    }

    @Test fun `cache recreation requires a full page even when revision matches`() {
        val initial = ChatState.latest(ChatContent(), page(1..5))
        val unchanged = page(IntRange.EMPTY, epoch = "new-cache").copy(unchanged = true)
        assertTrue(ChatState.requiresFullPage(initial, unchanged))
        assertFalse(ChatState.requiresFullPage(initial, unchanged.copy(historyEpoch = "e1")))
    }

    @Test fun `retrying reads block submission until an authoritative same conversation page restores readiness`() {
        val id = "00000000-0000-4000-8000-000000000001"
        val initial = ChatState.latest(ChatContent(), page(1..5).copy(conversationId = id))
            .copy(draft = "reviewed draft", readRetrying = true)
        assertFalse("Clearing a displayed error must not bypass an ongoing failed read", initial.canSubmitMessage())
        val recovered = ChatState.latest(initial, page(1..6).copy(conversationId = id))
        assertFalse(recovered.readRetrying)
        assertEquals("reviewed draft", recovered.draft)
        assertEquals(id, recovered.conversationId)
        assertTrue(recovered.canSubmitMessage())
        val uncertain = ChatState.latest(initial.copy(deliveryUncertain = true, error = "Inspect delivery"), page(1..6).copy(conversationId = id))
        assertFalse(uncertain.readRetrying)
        assertTrue(uncertain.deliveryUncertain)
        assertEquals("Inspect delivery", uncertain.error)
        assertFalse(uncertain.canSubmitMessage())
    }

    @Test fun `read recovery from a different UUID ends retrying but keeps the original conversation guarded`() {
        val initial = ChatState.latest(ChatContent(), page(1..5).copy(conversationId = "00000000-0000-4000-8000-000000000001"))
            .copy(draft = "original draft", readRetrying = true, error = "Temporary read failure")
        val guarded = ChatState.latest(initial, page(6..10).copy(conversationId = "00000000-0000-4000-8000-000000000002"))
        assertFalse(guarded.readRetrying)
        assertTrue(guarded.conversationChanged)
        assertEquals(initial.messages, guarded.messages)
        assertEquals(initial.draft, guarded.draft)
        assertEquals(initial.conversationId, guarded.conversationId)
        assertFalse(guarded.canSubmitMessage())
    }

    @Test fun `uncertain delivery and draft survive reads until user review or acknowledged resend`() {
        val initial = ChatState.latest(ChatContent(), page(51..100)).copy(draft = "not confirmed", deliveryUncertain = true, error = "Check before resending")
        val updated = ChatState.latest(initial, page(52..101, revision = "r2"))
        assertTrue(updated.deliveryUncertain)
        assertEquals("Check before resending", updated.error)
        val older = ChatState.older(updated, page(1..50, revision = "r2"))
        assertTrue(older.deliveryUncertain)
        val acknowledged = ChatState.acknowledged(older, "b1", "not confirmed", null)
        assertFalse(acknowledged.deliveryUncertain)
        assertEquals("", acknowledged.draft)
    }
}
