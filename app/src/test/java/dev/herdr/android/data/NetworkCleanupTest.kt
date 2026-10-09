package dev.herdr.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Callback
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class NetworkCleanupTest {
    private val request = Request.Builder().url("http://127.0.0.1:1/").build()
    private val callback = object : Callback {
        override fun onFailure(call: Call, error: IOException) = Unit
        override fun onResponse(call: Call, response: Response) = response.close()
    }

    @Test fun `closing retires the client immediately and submits cleanup only once`() {
        val pendingCleanup = mutableListOf<Runnable>()
        val http = OkHttpClient()
        val lifecycle = HttpClientLifecycle(http, Executor { pendingCleanup.add(it) })
        lifecycle.close()
        lifecycle.close()

        assertEquals(1, pendingCleanup.size)
        val call = http.newCall(request)
        assertThrows(IOException::class.java) { lifecycle.enqueue(call, callback) { true } }
        assertFalse(call.isExecuted())
        pendingCleanup.single().run()
    }

    @Test fun `an already canceled continuation cannot enqueue while socket cleanup waits`() {
        val http = OkHttpClient()
        val lifecycle = HttpClientLifecycle(http)
        val call = http.newCall(request)
        assertFalse(lifecycle.enqueue(call, callback) { false })
        assertFalse(call.isExecuted())
        lifecycle.close()
    }

    @Test fun `a finished callback never evicts connections while the client remains open`() {
        val pendingCleanup = mutableListOf<Runnable>()
        val lifecycle = HttpClientLifecycle(OkHttpClient(), Executor { pendingCleanup.add(it) })
        lifecycle.finishedCall()
        assertTrue(pendingCleanup.isEmpty())
        lifecycle.close()
        pendingCleanup.single().run()
    }

    @Test fun `a callback finishing after retirement schedules eviction of late pooled connections`() {
        MockWebServer().use { server ->
            server.start()
            val http = OkHttpClient()
            val pendingCleanup = mutableListOf<Runnable>()
            val lifecycle = HttpClientLifecycle(http, Executor { pendingCleanup.add(it) })
            lifecycle.close()
            pendingCleanup.removeAt(0).run()
            assertEquals(0, http.connectionPool.connectionCount())

            // Model an active HTTP/2 body returning its reusable connection after
            // the initial eviction: a real pooled socket must then be released too.
            server.enqueue(MockResponse().setBody("late completed body"))
            http.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                assertEquals("late completed body", it.body!!.string())
            }
            assertEquals(1, http.connectionPool.idleConnectionCount())
            lifecycle.finishedCall()
            assertEquals(1, pendingCleanup.size)
            assertEquals(1, http.connectionPool.idleConnectionCount())
            pendingCleanup.removeAt(0).run()
            assertEquals(0, http.connectionPool.connectionCount())
        }
    }

    @Test fun `call cancellation runs away from the calling thread without a caller scope`() {
        val canceledThread = AtomicReference<Thread>()
        val canceled = CountDownLatch(1)
        val http = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun canceled(call: Call) {
                canceledThread.set(Thread.currentThread())
                canceled.countDown()
            }
        }).build()
        val call = http.newCall(request)
        val caller = Thread.currentThread()

        NetworkCleanup.cancel(call)

        assertTrue(canceled.await(5, TimeUnit.SECONDS))
        assertTrue(call.isCanceled())
        assertNotSame(caller, canceledThread.get())
    }

    @Test fun `a retired server client refuses new requests and terminal observers`() {
        val client = HerdrClient(ServerCredentials(ServerAddress.validate("http://127.0.0.1:1/"), "test-token"))
        client.close()
        client.close()
        assertThrows(IOException::class.java) { runBlocking { client.health() } }
        assertThrows(IOException::class.java) {
            runBlocking { client.observe("test-pane", 80, 30).collect {} }
        }
    }

    @Test fun `a retired pairing client refuses a new invitation exchange`() {
        val client = PairingClient()
        client.close()
        client.close()
        val invitation = PairingPayload.parse(
            """{"type":"herdr-pocket-pair","version":1,"url":"http://127.0.0.1:1/","name":"Test","code":"${"A".repeat(43)}","expiresAt":1}""",
        )
        assertThrows(IOException::class.java) { runBlocking { client.pair(invitation, "Phone") } }
    }
}
