package dev.herdr.android.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

class PairingClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: PairingClient
    private val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 3 })
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 4 })
    private val response get() = """{"service":"herdr-android-bridge","protocol":1,"token":"$token","deviceId":"12345678-1234-4234-9234-123456789abc","serverName":"Test server"}"""
    private val invitation get() = PairingPayload.parse("""{"type":"herdr-pocket-pair","version":1,"url":"${server.url("/")}","name":"Test server","code":"$code","expiresAt":1}""")

    @Before fun setup() { server = MockWebServer().apply { start() }; client = PairingClient() }
    @After fun teardown() { client.close(); server.shutdown() }

    @Test fun `pairing sends a code in its body without bearer auth or URL credentials`() = runBlocking {
        server.enqueue(MockResponse().setBody(response))
        val paired = client.pair(invitation, "Android test phone")
        assertEquals(token, paired.token)
        val request = server.takeRequest(1, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/v1/pair", request.path)
        assertNull(request.getHeader("Authorization"))
        assertFalse(request.path!!.contains(code))
        assertEquals("""{"code":"$code","deviceName":"Android test phone"}""", request.body.readUtf8())
    }

    @Test fun `503 retry advice cannot redeem the pairing code twice`() {
        server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("""{"detail":"unavailable"}"""))
        server.enqueue(MockResponse().setBody(response))
        assertThrows(PairingException::class.java) { runBlocking { client.pair(invitation, "Phone") } }
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `uncertain pairing delivery is never replayed automatically`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        server.enqueue(MockResponse().setBody(response))
        val error = assertThrows(PairingException::class.java) { runBlocking { client.pair(invitation, "Phone") } }
        assertFalse(error.toString().contains(code))
        assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }

    @Test fun `pairing redirects do not forward a code to another server`() {
        val destination = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(307).addHeader("Location", destination.url("/stolen")))
            assertThrows(PairingException::class.java) { runBlocking { client.pair(invitation, "Phone") } }
            assertNull(destination.takeRequest(300, TimeUnit.MILLISECONDS))
        } finally { destination.shutdown() }
    }

    @Test fun `invalid successful responses and rejection bodies never expose credentials`() {
        server.enqueue(MockResponse().setBody(response.replace("\"protocol\":1", "\"protocol\":\"1\"")))
        val invalid = assertThrows(PairingException::class.java) { runBlocking { client.pair(invitation, "Phone") } }
        assertFalse(invalid.toString().contains(token))
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"detail":"$code $token"}"""))
        val rejected = assertThrows(PairingException::class.java) { runBlocking { client.pair(invitation, "Phone") } }
        assertFalse(rejected.toString().contains(code))
        assertFalse(rejected.toString().contains(token))
        // Coroutine stacktrace recovery may clone this safe exception as a cause.
        generateSequence<Throwable>(rejected) { it.cause }.take(8).forEach { cause ->
            assertFalse(cause.toString().contains(code))
            assertFalse(cause.toString().contains(token))
        }
        server.enqueue(MockResponse().setBody(" ".repeat(16385)))
        assertThrows(PairingException::class.java) { runBlocking { client.pair(invitation, "Phone") } }
    }

    @Test fun `canceling a pending pairing cancels its HTTP call without replay`() = runBlocking {
        server.enqueue(MockResponse().setHeadersDelay(1, TimeUnit.SECONDS).setBody(response))
        val job = async(Dispatchers.IO) { client.pair(invitation, "Phone") }
        assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
    }
}
