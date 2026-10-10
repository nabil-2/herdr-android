package dev.herdr.android.data

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.BufferedSink
import java.io.IOException
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class ServerCredentials(val baseUrl: HttpUrl, val token: String) {
    override fun toString(): String = "ServerCredentials(redacted)"
}

class BridgeException(val statusCode: Int, message: String) : IOException(message)
class TerminalEndedException(message: String) : IOException(message)

internal class HerdrClient(
    private val credentials: ServerCredentials,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false) // A timed-out write may already have reached the agent.
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val lifecycle = HttpClientLifecycle(http)

    suspend fun health(): Health {
        val health: Health = json.decodeFromString(request("GET", listOf("v1", "health")))
        require(health.service == "herdr-android-bridge" && health.protocol == 1) {
            "The server does not support Herdr Android protocol 1."
        }
        return health
    }

    suspend fun snapshot(): Snapshot = withContext(Dispatchers.Default) {
        SnapshotProtocol.decode(request("GET", listOf("v1", "snapshot")))
    }

    suspend fun conversation(paneId: String, cursor: String? = null, revision: String? = null): ConversationPage {
        cursor?.let { ConversationProtocol.validateOpaque(it, 2048) }
        revision?.let { ConversationProtocol.validateOpaque(it, 256) }
        val query = if (cursor != null) mapOf("cursor" to cursor) else revision?.let { mapOf("revision" to it) }.orEmpty()
        return ConversationProtocol.decode(request("GET", listOf("v1", "panes", paneId, "conversation"), query = query, maxBytes = 1024L * 1024L, chatWorkingInput = true))
    }

    suspend fun sendMessage(paneId: String, binding: String, text: String, attachmentIds: List<String> = emptyList()): String? =
        ConversationProtocol.acknowledgement(request("POST", listOf("v1", "panes", paneId, "messages"), ConversationProtocol.input(binding, text, attachmentIds), chatWorkingInput = true))

    suspend fun codexConversations(paneId: String): CodexConversationChoices = CodexConversationSelectionProtocol.decode(
        request("GET", listOf("v1", "panes", paneId, "codex-conversations"), maxBytes = 64 * 1024),
    )

    suspend fun selectCodexConversation(paneId: String, id: String, selectionToken: String) {
        requireInputAcknowledgement(request("POST", listOf("v1", "panes", paneId, "codex-conversation"), CodexConversationSelectionProtocol.input(id, selectionToken)))
    }

    suspend fun uploadChatAttachment(paneId: String, binding: String, name: String, kind: ChatAttachmentKind, file: File): UploadedChatAttachment {
        ConversationProtocol.validateOpaque(binding, 256)
        ChatAttachmentProtocol.validateName(name)
        val size = withContext(Dispatchers.IO) { file.length() }
        require(size in 1..ChatAttachmentProtocol.MAX_FILE_BYTES) { "Choose a file smaller than 16 MiB." }
        val url = endpoint(listOf("v1", "panes", paneId, "attachments")).newBuilder()
            .addQueryParameter("kind", kind.wireName).addQueryParameter("name", name).build()
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = size
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) {
                file.inputStream().use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var remaining = size
                    while (remaining > 0) {
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) throw IOException("The selected attachment changed during upload.")
                        sink.write(buffer, 0, count)
                        remaining -= count
                    }
                    if (input.read() != -1) throw IOException("The selected attachment changed during upload.")
                }
            }
        }
        val request = authenticatedRequest(url).header("X-Herdr-Chat-Binding", binding).post(body).build()
        val call = http.newCall(request)
        call.timeout().timeout(65, TimeUnit.SECONDS) // The host permits a 60-second streamed upload.
        return ChatAttachmentProtocol.decode(call.awaitBody(8 * 1024), name, kind, size)
    }

    suspend fun deleteChatAttachment(paneId: String, binding: String, id: String) {
        ConversationProtocol.validateOpaque(binding, 256)
        ChatAttachmentProtocol.validateIds(listOf(id))
        val request = authenticatedRequest(endpoint(listOf("v1", "panes", paneId, "attachments", id)))
            .header("X-Herdr-Chat-Binding", binding).delete().build()
        requireInputAcknowledgement(http.newCall(request).awaitBody(8 * 1024))
    }

    suspend fun acknowledgeChatAttachments(paneId: String, binding: String) {
        ConversationProtocol.validateOpaque(binding, 256)
        val delegate = ByteArray(0).toRequestBody(null)
        val body = object : RequestBody() {
            override fun contentType() = delegate.contentType()
            override fun contentLength() = 0L
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = Unit
        }
        val request = authenticatedRequest(endpoint(listOf("v1", "panes", paneId, "attachments", "acknowledge")))
            .header("X-Herdr-Chat-Binding", binding).post(body).build()
        requireInputAcknowledgement(http.newCall(request).awaitBody(8 * 1024))
    }

    suspend fun input(paneId: String, text: String?, keys: List<String>) {
        val body = InputValidation.body(text, keys)
        val response = request("POST", listOf("v1", "panes", paneId, "input"), body)
        requireInputAcknowledgement(response)
    }

    /** Literal terminal bytes decoded as strict UTF-8, including controls. */
    suspend fun terminalInput(paneId: String, text: String) {
        require(text.isNotEmpty()) { "Terminal input must not be empty." }
        require(text.codePointCount(0, text.length) <= 16384 && text.toByteArray(Charsets.UTF_8).size <= 32768) {
            "Terminal input exceeds the host limit. Paste smaller sections."
        }
        val body = json.encodeToString(RawTerminalInput(text))
        InputValidation.validateBody(body)
        requireInputAcknowledgement(request("POST", listOf("v1", "panes", paneId, "terminal-input"), body))
    }

    suspend fun terminalHistory(paneId: String): TerminalHistory = TerminalHistoryProtocol.decode(
        request("GET", listOf("v1", "panes", paneId, "scrollback"), maxBytes = 512L * 1024L), paneId,
    )

    private fun requireInputAcknowledgement(response: String) {
        val acknowledgement = json.parseToJsonElement(response).jsonObject["ok"] as? JsonPrimitive
        if (acknowledgement == null || acknowledgement.isString || acknowledgement.booleanOrNull != true) {
            throw IOException("The bridge did not acknowledge the input.")
        }
    }

    suspend fun createWorkspace(cwd: String, label: String): String {
        require(cwd.isNotBlank() && cwd.length <= 4096) { "Enter a working directory on the server." }
        require(label.isNotBlank() && label.length <= 100) { "Enter a workspace name (up to 100 characters)." }
        val body = request("POST", listOf("v1", "workspaces"), json.encodeToString(WorkspaceInput(cwd.trim(), label.trim())))
        return json.decodeFromString<CreatedWorkspace>(body).id.also {
            require(it.isNotBlank()) { "The bridge did not return a workspace ID." }
        }
    }

    suspend fun createTab(workspaceId: String, label: String, cwd: String, codex: Boolean): CreatedTab {
        val input = TabCreationProtocol.input(workspaceId, label, cwd, codex)
        return TabCreationProtocol.decode(
            request("POST", listOf("v1", "workspaces", workspaceId, "tabs"), input), workspaceId,
        )
    }

    fun observe(paneId: String, cols: Int, rows: Int): Flow<TerminalMessage> = callbackFlow {
        val url = endpoint(listOf("v1", "terminals", paneId, "stream")).newBuilder()
            .addQueryParameter("cols", cols.coerceIn(20, 300).toString())
            .addQueryParameter("rows", rows.coerceIn(5, 150).toString()).build()
        val socket = lifecycle.newWebSocket(authenticatedRequest(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = TerminalProtocol.decode(text)
                    if (message is TerminalMessage.Closed) {
                        close(TerminalEndedException(safeMessage(message.reason)))
                        webSocket.close(1000, "Terminal ended")
                    } else if (trySend(message).isFailure) {
                        // A fresh observer will send a complete screen. Never silently lose ANSI deltas.
                        close(IOException("Terminal updates arrived too quickly; reconnecting."))
                        lifecycle.cancel(webSocket)
                    }
                } catch (error: Exception) {
                    close(IOException("Invalid terminal frame received.", error))
                    lifecycle.cancel(webSocket)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                close(IOException("The bridge sent an unsupported binary terminal frame."))
                lifecycle.cancel(webSocket)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                close(IOException("Terminal connection closed."))
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val status = response?.code
                response?.close()
                close(if (status != null) BridgeException(status, statusMessage(status)) else IOException("Could not reach the terminal stream."))
            }
        })
        awaitClose { lifecycle.cancel(socket) }
    }

    fun close() {
        lifecycle.close()
    }

    private fun endpoint(segments: List<String>): HttpUrl = credentials.baseUrl.newBuilder().apply {
        segments.forEach(::addPathSegment)
    }.build()

    private fun authenticatedRequest(url: HttpUrl): Request.Builder = Request.Builder()
        .url(url)
        .header("Authorization", "Bearer ${credentials.token}")
        .header("Accept", "application/json")

    private suspend fun request(
        method: String,
        segments: List<String>,
        body: String? = null,
        query: Map<String, String> = emptyMap(),
        maxBytes: Long = MAX_HTTP_BYTES,
        chatWorkingInput: Boolean = false,
    ): String {
        val url = endpoint(segments).newBuilder().apply { query.forEach { (name, value) -> addQueryParameter(name, value) } }.build()
        val builder = authenticatedRequest(url)
        if (chatWorkingInput) {
            builder.header("X-Herdr-Chat-Working-Input", "1")
            builder.header("X-Herdr-Chat-Fresh-Input", "1")
        }
        if (method == "POST") {
            val delegate = (body ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())
            builder.post(object : RequestBody() {
                override fun contentType() = delegate.contentType()
                override fun contentLength() = delegate.contentLength()
                // Connection retry configuration alone does not disable 503/421 follow-ups.
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = delegate.writeTo(sink)
            })
        }
        return http.newCall(builder.build()).awaitBody(maxBytes)
    }

    private suspend fun Call.awaitBody(maxBytes: Long): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { NetworkCleanup.cancel(this) }
        val callback = object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                try {
                    if (continuation.isActive) continuation.resumeWithException(IOException("Could not reach the server. Check Tailscale and the bridge."))
                } finally {
                    lifecycle.finishedCall()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        val body = it.body ?: throw IOException("The server returned an empty response.")
                        val source = body.source()
                        if (source.request(maxBytes + 1) && source.buffer.size > maxBytes) {
                            throw IOException("The server response is too large.")
                        }
                        val text = source.readUtf8()
                        if (!it.isSuccessful) {
                            val detail = runCatching {
                                json.parseToJsonElement(text).jsonObject["detail"]?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                            throw BridgeException(it.code, safeMessage(detail ?: statusMessage(it.code)))
                        }
                        if (continuation.isActive) continuation.resume(text)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                } finally {
                    lifecycle.finishedCall()
                }
            }
        }
        try {
            lifecycle.enqueue(this, callback) { continuation.isActive }
        } catch (error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }
    }

    private fun safeMessage(value: String): String = value.replace(credentials.token, "[redacted]").take(500)

    private fun statusMessage(status: Int): String = when (status) {
        401 -> "The bridge rejected this token. Edit the server profile to enter a valid token."
        403 -> "This server denied access."
        409 -> "The selected pane changed. Refresh before sending again."
        503 -> "Herdr is unavailable on this server."
        in 300..399 -> "The server redirected the request. Enter its final HTTPS URL."
        else -> "The bridge returned HTTP $status."
    }

    private companion object { const val MAX_HTTP_BYTES = 2L * 1024L * 1024L }
}
