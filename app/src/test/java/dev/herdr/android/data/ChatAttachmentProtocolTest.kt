package dev.herdr.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ChatAttachmentProtocolTest {
    private val id = "a".repeat(32)
    private fun receipt(name: String = "image.png", kind: String = "image", size: Long = 123) =
        """{"id":"$id","name":"$name","kind":"$kind","mimeType":"image/png","sizeBytes":$size}"""

    @Test fun `attachment only input has explicit IDs and ordinary input remains compatible`() {
        val body = Json.parseToJsonElement(ConversationProtocol.input("binding", "", listOf(id))).jsonObject
        assertEquals(listOf(id), body["attachmentIds"]!!.jsonArray.map { it.toString().trim('"') })
        assertEquals("\"\"", body["text"].toString())
        assertEquals(setOf("binding", "text"), Json.parseToJsonElement(ConversationProtocol.input("binding", "Hello")).jsonObject.keys)
    }

    @Test fun `invalid or duplicate receipt IDs never become sendable input`() {
        listOf(listOf("../path"), listOf(id.uppercase()), listOf(id, id), (0..8).map { it.toString().padStart(32, '0') }).forEach {
            assertThrows(IllegalArgumentException::class.java) { ConversationProtocol.input("binding", "", it) }
        }
        assertThrows(IllegalArgumentException::class.java) { ConversationProtocol.input("binding", "bad\u001btext", listOf(id)) }
        assertThrows(IllegalArgumentException::class.java) { ConversationProtocol.input("binding", "😀".repeat(8193), listOf(id)) }
    }

    @Test fun `receipt must match the reviewed bytes name and kind`() {
        assertEquals(id, ChatAttachmentProtocol.decode(receipt(), "image.png", ChatAttachmentKind.IMAGE, 123).id)
        listOf(receipt("other.png"), receipt(kind = "file"), receipt(size = 124), receipt().replace(id, "wrong"),
            receipt().replace("image/png", "text/html"), receipt().replace("123", "\"123\""))
            .forEach { invalid -> assertThrows(IOException::class.java) { ChatAttachmentProtocol.decode(invalid, "image.png", ChatAttachmentKind.IMAGE, 123) } }
    }

    @Test fun `names cannot cross directories contain controls or exceed Unicode limits`() {
        listOf("../x", "folder\\x", ".", "..", " ", "x\n", "雪".repeat(161), "😀".repeat(161)).forEach {
            assertThrows(IllegalArgumentException::class.java) { ChatAttachmentProtocol.validateName(it) }
        }
        ChatAttachmentProtocol.validateName("Review 雪 📷.png")
    }

    @Test fun `every unfinished stage and either delivery uncertainty blocks sending`() {
        val ready = ChatAttachment("local", "image.png", ChatAttachmentKind.IMAGE, remoteId = id, status = ChatAttachmentStatus.READY)
        val chat = ChatContent(bindingId = "binding", canSend = true, status = "working", attachments = listOf(ready))
        assertTrue(chat.canSubmitMessage())
        ChatAttachmentStatus.entries.filter { it != ChatAttachmentStatus.READY }.forEach {
            assertFalse(chat.copy(attachments = listOf(ready.copy(status = it))).canSubmitMessage())
        }
        assertFalse(chat.copy(attachmentDeliveryUncertain = true).canSubmitMessage())
        assertFalse(chat.copy(attachments = listOf(ready.copy(remoteId = null))).canSubmitMessage())
    }

    @Test fun `acknowledgement drops only sent receipts and clears only the acknowledged draft`() {
        val submitted = ChatAttachment("old", "image.png", ChatAttachmentKind.IMAGE, remoteId = id, status = ChatAttachmentStatus.READY)
        val next = submitted.copy(localId = "next", remoteId = "b".repeat(32))
        val chat = ChatContent(bindingId = "binding", draft = "Next draft", attachments = listOf(submitted, next), sending = true)
        val result = ChatState.acknowledged(chat, "binding", "First draft", null, listOf(id))
        assertEquals("Next draft", result.draft)
        assertEquals(listOf(next), result.attachments)
        assertEquals(1L, result.ackRevision)
        assertFalse(result.sending)
    }

    @Test fun `switching scopes restores consumed receipt uncertainty without unsafe reusable IDs`() {
        val drafts = ChatDraftStore()
        drafts.remember("profile", "pane", ChatContent(bindingId = "binding", sending = true,
            attachments = listOf(ChatAttachment("old", "image.png", ChatAttachmentKind.IMAGE, remoteId = id, status = ChatAttachmentStatus.READY))))
        val restored = drafts.restore("profile", "pane", ChatContent(bindingId = "binding", canSend = true, status = "working"))
        assertTrue(restored.deliveryUncertain)
        assertTrue(restored.attachmentDeliveryUncertain)
        assertTrue(restored.attachments.isEmpty())
        assertFalse(restored.canSubmitMessage())
        assertFalse(drafts.restore("other-profile", "pane", ChatContent(bindingId = "binding")).attachmentDeliveryUncertain)
    }

    @Test fun `host uncertainty restores the explicit acknowledgement gate after reconnect and polling cannot clear it`() {
        val page = """{"binding":"binding","revision":"r","historyEpoch":"e","messages":[],"unchanged":false,"canSend":false,"status":"working","attachmentDeliveryUncertain":true}"""
        val current = ChatState.latest(ChatContent(), ConversationProtocol.decode(page))
        assertTrue(current.deliveryUncertain)
        assertTrue(current.attachmentDeliveryUncertain)
        assertFalse(current.canSubmitMessage())
        val later = ChatState.latest(current, ConversationProtocol.decode(page.replace("\"attachmentDeliveryUncertain\":true", "\"attachmentDeliveryUncertain\":false")))
        assertTrue(later.attachmentDeliveryUncertain)
        assertThrows(IOException::class.java) { ConversationProtocol.decode(page.replace("\"attachmentDeliveryUncertain\":true", "\"attachmentDeliveryUncertain\":\"true\"")) }
    }
}
