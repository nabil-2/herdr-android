package dev.herdr.android.data

import android.app.Application
import android.os.NetworkOnMainThreadException
import android.os.StrictMode
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.HerdrViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Connection
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Conscrypt TLS sockets reproduce the Android 13 post-Connect shutdown crash. */
@RunWith(AndroidJUnit4::class)
class TlsCleanupDeviceTest {
    @Test
    fun synchronousIdleTlsEvictionReproducesTheMainThreadNetworkException() {
        TlsFixture().use { fixture ->
            fixture.server.enqueue(json(HEALTH))
            fixture.http.newCall(Request.Builder().url(fixture.server.url("/v1/health")).build())
                .execute().use { assertEquals(200, it.code); assertEquals(HEALTH, it.body!!.string()) }
            fixture.awaitIdleSocket()

            // This is the original close() implementation, under Android's actual guard.
            val thrown = strictMain { fixture.http.connectionPool.evictAll() }
            assertTrue("The TLS fixture must exercise network work during socket close", thrown is NetworkOnMainThreadException)
        }
    }

    @Test
    fun herdrClientCanRepeatedlyCloseAnIdleTlsConnectionOnTheMainThread() {
        TlsFixture().use { fixture ->
            fixture.server.enqueue(json(HEALTH))
            val client = HerdrClient(ServerCredentials(fixture.server.url("/"), TEST_TOKEN), http = fixture.http)
            try {
                runBlocking { assertEquals("herdr-android-bridge", client.health().service) }
                fixture.awaitIdleSocket()

                assertNull(strictMain { client.close(); client.close() })
                fixture.awaitClosedSocket()
            } finally { client.close() }
        }
    }

    @Test
    fun pairingClientCanCloseTheSuccessfulTlsPairingConnectionOnTheMainThread() {
        TlsFixture().use { fixture ->
            fixture.server.enqueue(json(PAIR_RESULT))
            val client = PairingClient(http = fixture.http)
            try {
                val invitation = PairingInvitation("TLS pairing fixture", fixture.server.url("/"), TEST_CODE, Long.MAX_VALUE)
                runBlocking { assertEquals(TEST_TOKEN, client.pair(invitation, "Android TLS test").token) }
                fixture.awaitIdleSocket()

                assertNull(strictMain { client.close(); client.close() })
                fixture.awaitClosedSocket()
            } finally { client.close() }
        }
    }

    @Test
    fun cancellingAnInflightTlsRequestOnTheMainThreadClosesTheSocketAndFinishesTheCall() {
        TlsFixture().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val client = HerdrClient(ServerCredentials(fixture.server.url("/"), TEST_TOKEN), http = fixture.http)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val request = scope.async { client.health() }
            try {
                assertNotNull("The request must reach the TLS fixture before cancellation", fixture.server.takeRequest(10, TimeUnit.SECONDS))
                assertNull(strictMain { client.close() })

                val result = runBlocking { withTimeout(10_000) { runCatching { request.await() } } }
                assertTrue("Shutdown must terminate an in-flight request", result.isFailure)
                fixture.awaitClosedSocket()
                await("The cancelled call must leave the dispatcher") { fixture.http.dispatcher.runningCallsCount() == 0 }
            } finally { client.close(); scope.cancel() }
        }
    }

    @Test
    fun confirmingANewPairingAfterTlsValidationReleasesThePreviousConnectionWithoutCrashing() {
        TlsFixture().use { previous ->
            previous.server.enqueue(json(HEALTH))
            val previousClient = HerdrClient(ServerCredentials(previous.server.url("/"), TEST_TOKEN), http = previous.http)
            runBlocking { previousClient.health() }
            previous.awaitIdleSocket()

            val pairingServer = MockWebServer()
            pairingServer.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = when (request.requestUrl!!.encodedPath) {
                    "/v1/pair" -> json(PAIR_RESULT)
                    "/v1/health" -> json(HEALTH)
                    "/v1/snapshot" -> json("""{"workspaces":[],"tabs":[],"panes":[]}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            pairingServer.start()
            val pairingUrl = pairingServer.url("/").toString()
            val payload = """{"type":"herdr-pocket-pair","version":1,"url":"$pairingUrl","name":"TLS pairing fixture","code":"$TEST_CODE","expiresAt":9223372036854775807}"""
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val profiles = ProfileStore(context)
            val originalProfileIds = profiles.profiles().map { it.id }.toSet()
            val owner = ViewModelStore()
            lateinit var vm: HerdrViewModel
            try {
                assertNull(strictMain {
                    vm = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(
                        context.applicationContext as Application,
                    ))[HerdrViewModel::class.java]
                    // Warm a trusted local TLS connection, then exercise the real Connect handler.
                    // Reflection stays in the test; production certificate trust is unchanged.
                    HerdrViewModel::class.java.getDeclaredField("client").apply { isAccessible = true }.set(vm, previousClient)
                    vm.pairServer(payload)
                })
                await("Pairing must complete after the previous TLS connection closes") {
                    vm.state.value.connection == ConnectionState.CONNECTED
                }
                previous.awaitClosedSocket()
                assertFalse(vm.state.value.pairing)
                assertEquals(1L, vm.state.value.pairingSuccessRevision)
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.clear() }
                previousClient.close()
                profiles.profiles().filterNot { it.id in originalProfileIds }.forEach { profiles.remove(it.id) }
                pairingServer.shutdown()
            }
        }
    }

    /** Catch on the UI thread so the deliberately failing control does not kill the runner. */
    private fun strictMain(action: () -> Unit): Throwable? {
        val failure = AtomicReference<Throwable?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val original = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectNetwork().penaltyDeathOnNetwork().build())
            try { action() } catch (error: Throwable) { failure.set(error) }
            finally { StrictMode.setThreadPolicy(original) }
        }
        return failure.get()
    }

    private class TlsFixture : AutoCloseable {
        val server = MockWebServer()
        private val socket = AtomicReference<Socket?>()
        val http: OkHttpClient

        init {
            val certificate = HeldCertificate.Builder().commonName("localhost")
                .addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
            val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            server.useHttps(serverCertificates.sslSocketFactory(), false)
            server.protocols = listOf(Protocol.HTTP_1_1)
            server.start()
            http = OkHttpClient.Builder()
                .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
                .protocols(listOf(Protocol.HTTP_1_1))
                .retryOnConnectionFailure(false)
                .callTimeout(20, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun connectionAcquired(call: Call, connection: Connection) { socket.set(connection.socket()) }
                })
                .build()
        }

        fun awaitIdleSocket() {
            await("The fixture must retain an open, idle TLS socket") {
                socket.get()?.isClosed == false && http.connectionPool.idleConnectionCount() == 1
            }
        }

        fun awaitClosedSocket() {
            // Checking the captured socket also waits for actual closure, after pool removal.
            await("The TLS socket must actually close after client shutdown") {
                socket.get()?.isClosed == true && http.connectionPool.connectionCount() == 0
            }
        }

        override fun close() {
            // Cleanup is always on the instrumentation thread, including the failing control.
            http.dispatcher.cancelAll()
            http.connectionPool.evictAll()
            // The deliberately failing synchronous control already removed its socket
            // from the pool before TLS close threw, so close that captured socket too.
            socket.get()?.close()
            http.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    private companion object {
        val TEST_CODE = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 31 })
        val TEST_TOKEN = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 32 })
        const val HEALTH = """{"service":"herdr-android-bridge","protocol":1,"herdrVersion":"0.9.3"}"""
        val PAIR_RESULT = """{"service":"herdr-android-bridge","protocol":1,"token":"$TEST_TOKEN","deviceId":"12345678-1234-4234-9234-123456789abc","serverName":"TLS pairing fixture"}"""
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

        fun await(message: String, condition: () -> Boolean) {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (!condition() && System.nanoTime() < deadline) Thread.sleep(20)
            assertTrue(message, condition())
        }
    }
}
