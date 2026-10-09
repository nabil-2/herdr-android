package dev.herdr.android.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class TerminalHistoryTest {
    @Test fun `authenticated history is read only and retains Unicode line boundaries`() = runBlocking {
        val server = MockWebServer().apply { start() }
        val client = HerdrClient(ServerCredentials(ServerAddress.validate(server.url("/").toString()), "isolated-history-token"))
        try {
            server.enqueue(MockResponse().setBody(payload("雪😀\nsecond line\n")))
            val history = client.terminalHistory("w1:p1")
            assertEquals("w1:p1", history.paneId)
            assertEquals("雪😀\nsecond line\n", history.text)
            assertEquals(true, history.truncated)
            val request = server.takeRequest()
            assertEquals("GET", request.method)
            assertEquals("/v1/panes/w1:p1/scrollback", request.path)
            assertEquals("Bearer isolated-history-token", request.getHeader("Authorization"))
            assertEquals(0L, request.bodySize)
        } finally { client.close(); server.shutdown() }
    }

    @Test fun `history rejects another pane incorrect field types malformed Unicode and oversized text`() {
        listOf(
            """{"paneId":"w1:p2","text":"wrong pane","truncated":false}""",
            """{"paneId":"w1:p1","text":10,"truncated":false}""",
            """{"paneId":"w1:p1","text":"output","truncated":"false"}""",
            """{"paneId":"w1:p1","text":"output"}""",
            """{"paneId":"w1:p1","text":"\ud800","truncated":false}""",
            payload("雪".repeat(21846)), payload("line\n".repeat(1001)), "[]", "not-json",
        ).forEach { response ->
            assertThrows(IllegalArgumentException::class.java) { TerminalHistoryProtocol.decode(response, "w1:p1") }
        }
        val boundary = TerminalHistoryProtocol.decode(payload("a".repeat(65536)), "w1:p1")
        assertEquals(65536, boundary.text.length)
        assertFalse(boundary.toString().contains("aaaa"))
        assertEquals(1000, TerminalHistoryProtocol.decode(payload("x\n".repeat(1000)), "w1:p1").text.count { it == '\n' })
    }

    private fun payload(text: String) = Json.encodeToString(buildJsonObject {
        put("paneId", "w1:p1"); put("text", text); put("truncated", true)
    })
}
