package dev.herdr.android.data

import kotlinx.coroutines.Dispatchers
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.IOException
import java.util.concurrent.Executor
import kotlin.coroutines.EmptyCoroutineContext

/** Socket cancellation and TLS close-notify can perform network I/O, even for idle connections. */
internal object NetworkCleanup {
    // Dispatch directly rather than launching in a caller's scope: ViewModel cancellation must
    // not cancel the cleanup that releases its connections.
    val executor = Executor { task -> Dispatchers.IO.dispatch(EmptyCoroutineContext, task) }

    fun cancel(call: Call) = executor.execute { call.cancel() }
}

/** Retires a client synchronously, then releases its sockets on an independent I/O worker. */
internal class HttpClientLifecycle(
    private val http: OkHttpClient,
    private val cleanup: Executor = NetworkCleanup.executor,
) {
    private val lock = Any()
    private var closed = false
    private val sockets = mutableSetOf<WebSocket>()

    fun enqueue(call: Call, callback: Callback, isActive: () -> Boolean): Boolean = synchronized(lock) {
        requireOpen()
        if (!isActive()) return@synchronized false
        call.enqueue(callback)
        true
    }

    fun newWebSocket(request: Request, listener: WebSocketListener): WebSocket = synchronized(lock) {
        requireOpen()
        http.newWebSocket(request, listener).also(sockets::add)
    }

    fun cancel(socket: WebSocket) {
        synchronized(lock) { sockets.remove(socket) }
        cleanup.execute { socket.cancel() }
    }

    fun finishedCall() {
        // HTTP/2 cancellation can finish before the response body releases its
        // reusable connection. Evict again after callbacks finish releasing bodies.
        if (synchronized(lock) { closed }) cleanup.execute { http.connectionPool.evictAll() }
    }

    fun close() {
        val retiredSockets = synchronized(lock) {
            if (closed) return
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        cleanup.execute {
            try {
                try {
                    retiredSockets.forEach { it.cancel() }
                } finally {
                    http.dispatcher.cancelAll()
                }
            } finally {
                http.connectionPool.evictAll()
            }
        }
    }

    private fun requireOpen() {
        if (closed) throw IOException("This server connection is closed.")
    }
}
