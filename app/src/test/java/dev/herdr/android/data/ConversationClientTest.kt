package dev.herdr.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class ConversationClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HerdrClient
    private val token = "conversation-test-secret"
    private val page = """{"binding":"b1","revision":"r1","historyEpoch":"e1","messages":[{"id":"m1","role":"user","text":"Hello"}],"olderCursor":"cursor-1","unchanged":false,"canSend":true,"status":"idle","warning":null}"""

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = HerdrClient(ServerCredentials(ServerAddress.validate(server.url("/").toString()), token))
    }

    @After fun teardown() { client.close(); server.shutdown() }

    @Test fun `conversation polls authenticate and older cursor excludes latest revision query`() = runBlocking {
        server.enqueue(MockResponse().setBody(page))
        server.enqueue(MockResponse().setBody(page))
        assertEquals("m1", client.conversation("w1:p1", revision = "r1").messages.single().id)
        assertEquals("b1", client.conversation("w1:p1", cursor = "cursor-1", revision = "r1").binding)
        val latest = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/panes/w1:p1/conversation?revision=r1", latest.path)
        val older = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/panes/w1:p1/conversation?cursor=cursor-1", older.path)
        listOf(latest, older).forEach {
            assertEquals("Bearer $token", it.getHeader("Authorization"))
            assertFalse(it.path!!.contains(token))
        }
    }

    @Test fun `latest poll unchanged result still carries authoritative blocked state`() = runBlocking {
        server.enqueue(MockResponse().setBody(page.replace("""[{"id":"m1","role":"user","text":"Hello"}]""", "[]")
            .replace("\"unchanged\":false", "\"unchanged\":true").replace("\"canSend\":true", "\"canSend\":false").replace("\"idle\"", "\"blocked\"")))
        val result = client.conversation("w1:p1", revision = "r1")
        assertEquals(true, result.unchanged)
        assertEquals(false, result.canSend)
        assertEquals("blocked", result.status)
        assertEquals(0, result.messages.size)
    }

    @Test fun `chat send posts exact binding and multiline text without keys`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true,"warning":null}"""))
        assertNull(client.sendMessage("w1:p1", "b1", "first line\nsecond line"))
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("/v1/panes/w1:p1/messages", request.path)
        assertEquals("POST", request.method)
        assertEquals("""{"binding":"b1","text":"first line\nsecond line"}""", request.body.readUtf8())
        assertEquals("Bearer $token", request.getHeader("Authorization"))
    }

    @Test fun `503 retry advice cannot submit a message twice`() {
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("""{"detail":"Reader unavailable"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val error = assertThrows(BridgeException::class.java) { runBlocking { client.sendMessage("w1:p1", "b1", "run tests") } }
        assertEquals(503, error.statusCode)
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `lost message acknowledgement cannot replay a write`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertThrows(IOException::class.java) { runBlocking { client.sendMessage("w1:p1", "b1", "run tests") } }
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `busy stale and invalid success replies never acknowledge a draft`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"detail":"Conversation is busy"}"""))
        assertThrows(BridgeException::class.java) { runBlocking { client.sendMessage("w1:p1", "b1", "hello") } }
        server.takeRequest(1, TimeUnit.SECONDS)
        listOf("""{"ok":false}""", """{"ok":"true"}""", "{}").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertThrows(IOException::class.java) { runBlocking { client.sendMessage("w1:p1", "b1", "hello") } }
            server.takeRequest(1, TimeUnit.SECONDS)
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun `invalid draft or opaque binding never reaches the bridge`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.sendMessage("w1:p1", "b1", "😀".repeat(8193)) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.sendMessage("w1:p1", "bad binding", "hello") } }
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `conversation response size is bounded before decoding`() {
        server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
        assertThrows(IOException::class.java) { runBlocking { client.conversation("w1:p1") } }
    }

    @Test fun `older companions omit native chat capabilities and stay compatible`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"service":"herdr-android-bridge","protocol":1}"""))
        server.enqueue(MockResponse().setBody("""{"workspaces":[{"id":"w1","label":"Project","cwd":"/project"}],"tabs":[{"id":"w1:t1","workspaceId":"w1","label":"Main"}],"panes":[{"id":"w1:p1","workspaceId":"w1","tabId":"w1:t1","title":"Codex","cwd":"/project","agent":"codex"}]}"""))
        assertEquals(emptyList<String>(), client.health().capabilities)
        assertNull(client.snapshot().panes.single().conversation)
    }
}
