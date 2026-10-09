package dev.herdr.android.data

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Exchanges a one-use invitation for a device credential, without owner auth. */
internal class PairingClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build(),
) {
    private val lifecycle = HttpClientLifecycle(http)

    suspend fun pair(invitation: PairingInvitation, deviceName: String): PairingResult {
        try {
            PairingJson.serverName(deviceName)
        } catch (_: Exception) {
            throw PairingException("Choose a device name of up to 100 characters.")
        }
        val url = invitation.baseUrl.newBuilder().addPathSegment("v1").addPathSegment("pair").build()
        val body = JsonObject(mapOf("code" to JsonPrimitive(invitation.code), "deviceName" to JsonPrimitive(deviceName))).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .post(object : RequestBody() {
                override fun contentType() = body.contentType()
                override fun contentLength() = body.contentLength()
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
            }).build()
        return PairingJson.result(http.newCall(request).awaitResponse()).also {
            if (invitation.url.contains(it.token)) throw PairingException("The server returned an invalid pairing response.")
        }
    }

    fun close() {
        lifecycle.close()
    }

    private suspend fun Call.awaitResponse(): String = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { NetworkCleanup.cancel(this) }
        val callback = object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                try {
                    if (continuation.isActive) continuation.resumeWithException(
                        PairingException("Could not reach the pairing server. Check Tailscale and generate a new code before trying again."),
                    )
                } finally {
                    lifecycle.finishedCall()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use {
                        if (!it.isSuccessful) throw PairingException(when (it.code) {
                            401, 409, 410 -> "This pairing code expired or was already used. Generate a new code on your server."
                            503 -> "Herdr is unavailable. Start Herdr on the server before trying again."
                            in 300..399 -> "The pairing server redirected the request. Generate a code with its final HTTPS address."
                            else -> "The server rejected pairing. Generate a new code before trying again."
                        })
                        val source = it.body?.source() ?: throw PairingException("The server returned an empty pairing response.")
                        if (source.request(MAX_BYTES + 1) && source.buffer.size > MAX_BYTES) {
                            throw PairingException("The server returned an invalid pairing response.")
                        }
                        val text = source.readUtf8()
                        if (continuation.isActive) continuation.resume(text)
                    }
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(
                        if (error is PairingException) error else PairingException("Could not read the pairing response. Generate a new code before trying again."),
                    )
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

    private companion object { const val MAX_BYTES = 16384L }
}
