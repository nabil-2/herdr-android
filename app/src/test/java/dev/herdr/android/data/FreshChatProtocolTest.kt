package dev.herdr.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class FreshChatProtocolTest {
    private fun page(fresh: String = "true", status: String = "unknown") =
        """{"binding":"fresh-binding","revision":"r1","historyEpoch":"h1","messages":[],"unchanged":false,"canSend":true,"status":"$status","freshBootstrap":$fresh}"""

    @Test fun `new verified conversation accepts first message before history is saved`() {
        val content = ChatState.latest(ChatContent(), ConversationProtocol.decode(page()))
        assertTrue(content.freshBootstrap)
        assertTrue(content.canSubmitMessage())
        assertFalse(content.copy(sending = true).canSubmitMessage())
        assertFalse(content.copy(deliveryUncertain = true).canSubmitMessage())
        assertFalse(content.copy(status = "blocked").canSubmitMessage())
    }

    @Test fun `unknown ordinary conversation and malformed bootstrap proof stay rejected`() {
        listOf("false", "null", "1", "\"true\"").forEach { proof ->
            assertThrows(IOException::class.java) { ConversationProtocol.decode(page(proof)) }
        }
        assertThrows(IOException::class.java) { ConversationProtocol.decode(page().replace(",\"freshBootstrap\":true", "")) }
        assertFalse(ChatContent(bindingId = "existing", canSend = true, status = "unknown").canSubmitMessage())
    }

    @Test fun `saved-history refresh removes first-message allowance`() {
        val fresh = ChatState.latest(ChatContent(), ConversationProtocol.decode(page()))
        val persisted = ConversationProtocol.decode(page("false", "idle"))
        val updated = ChatState.latest(fresh, persisted)
        assertFalse(updated.freshBootstrap)
        assertTrue(updated.canSubmitMessage())
        assertFalse(updated.copy(status = "unknown").canSubmitMessage())
    }
}
