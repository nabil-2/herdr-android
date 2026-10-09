package dev.herdr.android.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class HerdrClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: HerdrClient
    private val token = "test-secret-never-in-url"

    @Before fun setup() {
        server = MockWebServer().apply { start() }
        client = HerdrClient(ServerCredentials(ServerAddress.validate(server.url("/").toString()), token))
    }

    @After fun teardown() { client.close(); server.shutdown() }

    @Test fun `health requires matching authenticated protocol and snapshot uses bearer header`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"service":"herdr-android-bridge","protocol":1,"herdrVersion":"0.9.3"}"""))
        server.enqueue(MockResponse().setBody("""{"herdrVersion":"0.9.3","workspaces":[],"tabs":[],"panes":[]}"""))
        assertEquals(1, client.health().protocol)
        assertEquals("0.9.3", client.snapshot().herdrVersion)
        repeat(2) {
            val request = server.takeRequest(1, TimeUnit.SECONDS)!!
            assertEquals("Bearer $token", request.getHeader("Authorization"))
            assertFalse(request.path!!.contains(token))
        }
    }

    @Test fun `rejected auth is never retried without credentials`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"bad token $token"}"""))
        val error = assertThrows(BridgeException::class.java) { runBlocking { client.health() } }
        assertEquals(401, error.statusCode)
        assertFalse(error.message!!.contains(token))
        assertEquals("Bearer $token", server.takeRequest().getHeader("Authorization"))
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `redirect cannot send bearer credentials to another host`() {
        val destination = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/stolen")))
            assertThrows(BridgeException::class.java) { runBlocking { client.health() } }
            assertNull(destination.takeRequest(200, TimeUnit.MILLISECONDS))
        } finally { destination.shutdown() }
    }

    @Test fun `uncertain command delivery is not automatically replayed`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertThrows(IOException::class.java) { runBlocking { client.input("w1:p1", "echo hello", listOf("enter")) } }
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/panes/w1:p1/input", request.path)
        assertEquals("""{"text":"echo hello","keys":["enter"]}""", request.body.readUtf8())
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `server retry advice cannot replay a command`() {
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("""{"detail":"Herdr unavailable"}"""))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val error = assertThrows(BridgeException::class.java) { runBlocking { client.input("w1:p1", "echo hello", listOf("enter")) } }
        assertEquals(503, error.statusCode)
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `server retry advice cannot create a workspace twice`() {
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("""{"detail":"Herdr unavailable"}"""))
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"w2"}"""))
        val error = assertThrows(BridgeException::class.java) { runBlocking { client.createWorkspace("/tmp/project", "project") } }
        assertEquals(503, error.statusCode)
        assertEquals("/v1/workspaces", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `a different service is not treated as Herdr`() {
        server.enqueue(MockResponse().setBody("""{"service":"other-service","protocol":1}"""))
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.health() } }
    }

    @Test fun `invalid pasted input never reaches the bridge`() {
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.input("w1:p1", "😀".repeat(8193), listOf("enter")) } }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { client.input("w1:p1", "bad\u0000paste", listOf("enter")) } }
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `literal terminal input authenticates and preserves controls NUL Unicode and escape sequences`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        val text = "雪😀\u0000\u0003\r\u001b[A\u001b[200~paste\u001b[201~"
        client.terminalInput("w1:p1", text)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/panes/w1:p1/terminal-input", request.path)
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("text"), body.keys)
        assertEquals(text, body["text"]!!.jsonPrimitive.contentOrNull)
    }

    @Test fun `invalid literal terminal sizes are refused before any request`() {
        listOf("", "a".repeat(16385), "😀".repeat(8193), "\u0000".repeat(16384)).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.terminalInput("w1:p1", text) } }
        }
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `literal keystrokes whose acknowledgement is lost are never replayed`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        assertThrows(IOException::class.java) { runBlocking { client.terminalInput("w1:p1", "\u0003") } }
        assertEquals("/v1/panes/w1:p1/terminal-input", server.takeRequest(1, TimeUnit.SECONDS)!!.path)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `literal terminal input rejects false acknowledgement and retry advice`() {
        listOf(MockResponse().setBody("""{"ok":false}"""),
            MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("""{"detail":"Unavailable"}""")).forEach { response ->
            server.enqueue(response)
            assertThrows(IOException::class.java) { runBlocking { client.terminalInput("w1:p1", "x") } }
            assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun `only a true acknowledgement reports command success and never retries`() {
        listOf("""{"ok":false}""", """{"type":"ok"}""", """{"ok":"true"}""", "not-json").forEach { body ->
            server.enqueue(MockResponse().setBody(body))
            assertThrows(Exception::class.java) { runBlocking { client.input("w1:p1", "echo hello", listOf("enter")) } }
            assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
            assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
        }
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))
        runBlocking { client.input("w1:p1", "echo hello", listOf("enter")) }
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
    }

    @Test fun `terminal stream authenticates and preserves full frame followed by ANSI delta`() = runBlocking {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"terminal.frame","bytes":"aGVsbG8=","encoding":"ansi","full":true,"width":80,"height":30,"seq":1}""")
                webSocket.send("""{"type":"terminal.frame","bytes":"G1sxQQ==","encoding":"ansi","full":false,"width":80,"height":30,"seq":2}""")
            }
        }))
        val frames = client.observe("w1:p1", 80, 30).take(2).toList().map { it as TerminalMessage.Frame }
        assertArrayEquals("hello".toByteArray(), frames[0].ansi)
        assertEquals(true, frames[0].full)
        assertArrayEquals("\u001b[1A".toByteArray(), frames[1].ansi)
        assertEquals(false, frames[1].full)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("Bearer $token", request.getHeader("Authorization"))
        assertEquals("/v1/terminals/w1:p1/stream?cols=80&rows=30", request.path)
    }
}
