package dev.herdr.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.HttpUrl
import java.io.IOException
import java.util.Base64
import java.util.UUID

class PairingException(message: String) : IOException(message)

/** Credentials are memory-only and deliberately absent from generated string output. */
class PairingInvitation internal constructor(
    val name: String,
    internal val baseUrl: HttpUrl,
    internal val code: String,
    val expiresAt: Long,
) {
    val url: String get() = baseUrl.toString()
    // A wrong phone clock must not prevent a valid server-authorized pairing.
    fun isPossiblyExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean = expiresAt <= nowEpochSeconds
    override fun toString(): String = "PairingInvitation(redacted)"
}

internal class PairingResult(val token: String, val deviceId: String, val serverName: String) {
    override fun toString(): String = "PairingResult(redacted)"
}

object PairingPayload {
    private val fields = setOf("type", "version", "url", "name", "code", "expiresAt")

    fun parse(payload: String): PairingInvitation {
        try {
            val data = PairingJson.flatObject(payload, 4096, fields)
            require(PairingJson.string(data, "type") == "herdr-pocket-pair")
            val version = PairingJson.primitive(data, "version")
            require(!version.isString && version.intOrNull == 1)
            val url = ServerAddress.validate(PairingJson.string(data, "url"))
            val name = PairingJson.serverName(PairingJson.string(data, "name"))
            val code = PairingJson.secret(PairingJson.string(data, "code"))
            require(!name.contains(code) && !url.toString().contains(code))
            val expiresAt = PairingJson.primitive(data, "expiresAt")
            require(!expiresAt.isString && (expiresAt.longOrNull ?: 0) > 0)
            return PairingInvitation(name, url, code, expiresAt.longOrNull!!)
        } catch (_: Exception) {
            // JSON parser diagnostics can include the supplied pairing code.
            throw PairingException("This is not a valid Herdr pairing QR code. Generate a new code on your server.")
        }
    }
}

internal object PairingJson {
    private val json = Json { isLenient = false }
    private val secretPattern = Regex("[A-Za-z0-9_-]{43}")

    fun flatObject(value: String, maxBytes: Int, fields: Set<String>): JsonObject {
        require(value.length <= maxBytes && value.toByteArray(Charsets.UTF_8).size <= maxBytes)
        val input = value.trim()
        require(input.startsWith('{') && input.endsWith('}'))
        var quoted = false
        var escaped = false
        var objectDepth = 0
        var separators = 0
        for (char in input) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{' -> { objectDepth++; require(objectDepth == 1) }
                '}' -> { objectDepth--; require(objectDepth == 0) }
                '[', ']' -> throw IllegalArgumentException()
                ':' -> separators++
            }
        }
        // A flat primitive-only object has exactly one separator per field. This
        // also rejects duplicate keys that JsonObject would otherwise overwrite.
        require(!quoted && objectDepth == 0 && separators == fields.size)
        val data = json.parseToJsonElement(input) as? JsonObject ?: throw IllegalArgumentException()
        require(data.keys == fields && data.values.all { it is JsonPrimitive })
        return data
    }

    fun primitive(data: JsonObject, key: String): JsonPrimitive = data[key] as? JsonPrimitive ?: throw IllegalArgumentException()

    fun string(data: JsonObject, key: String): String {
        val value = primitive(data, key)
        require(value.isString)
        return value.content
    }

    fun serverName(value: String): String {
        require(value.isNotBlank() && value.codePointCount(0, value.length) in 1..100 && value.none { it.code < 32 || it.code == 127 })
        return value
    }

    fun secret(value: String): String {
        require(secretPattern.matches(value))
        val bytes = Base64.getUrlDecoder().decode(value)
        require(bytes.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value)
        return value
    }

    fun result(payload: String): PairingResult {
        try {
            val data = flatObject(payload, 16384, setOf("service", "protocol", "token", "deviceId", "serverName"))
            require(string(data, "service") == "herdr-android-bridge")
            val protocol = primitive(data, "protocol")
            require(!protocol.isString && protocol.intOrNull == 1)
            val token = secret(string(data, "token"))
            val deviceId = string(data, "deviceId")
            require(UUID.fromString(deviceId).toString() == deviceId.lowercase())
            val serverName = serverName(string(data, "serverName"))
            require(!serverName.contains(token))
            return PairingResult(token, deviceId, serverName)
        } catch (_: Exception) {
            throw PairingException("The server returned an invalid pairing response. Generate a new code before trying again.")
        }
    }
}
