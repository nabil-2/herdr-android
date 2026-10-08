package dev.herdr.android.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingPayloadTest {
    private val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 1 })
    private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 2 })

    private fun qr(url: String = "https://host.example/", name: String = "My server"): String = JsonObject(mapOf(
        "type" to JsonPrimitive("herdr-pocket-pair"), "version" to JsonPrimitive(1),
        "url" to JsonPrimitive(url), "name" to JsonPrimitive(name), "code" to JsonPrimitive(code), "expiresAt" to JsonPrimitive(1234567890),
    )).toString()

    private fun response(): String = """{"service":"herdr-android-bridge","protocol":1,"token":"$token","deviceId":"12345678-1234-4234-9234-123456789abc","serverName":"My server"}"""

    @Test fun `valid QR exposes bounded metadata and treats clock expiry as a hint`() {
        val parsed = PairingPayload.parse(qr())
        assertEquals("My server", parsed.name)
        assertEquals("https://host.example/", parsed.url)
        assertEquals(1234567890L, parsed.expiresAt)
        assertFalse(parsed.isPossiblyExpired(1234567889))
        assertTrue(parsed.isPossiblyExpired(1234567891))
        assertFalse(parsed.toString().contains(code))
    }

    @Test fun `QR parser rejects wrong types fields duplicates nesting and excessive input without echoing secrets`() {
        val valid = qr()
        listOf(
            "https://host.example/", valid.replace("\"version\":1", "\"version\":\"1\""),
            valid.replace("\"expiresAt\":1234567890", "\"expiresAt\":0"),
            valid.replace("herdr-pocket-pair", "other-app"),
            valid.dropLast(1) + ",\"type\":\"herdr-pocket-pair\"}",
            valid.dropLast(1) + ",\"extra\":true}",
            valid.replace("\"name\":\"My server\"", "\"name\":{}"),
            "{".repeat(4000), valid + " ".repeat(4096),
        ).forEach {
            val error = assertThrows(PairingException::class.java) { PairingPayload.parse(it) }
            assertFalse(error.toString().contains(code))
            assertNull(error.cause)
        }
    }

    @Test fun `pairing QR follows the same HTTPS and private numeric URL policy as profiles`() {
        listOf("http://example.com", "http://host.tailnet.ts.net", "https://secret@host.example/", "https://host.example/?code=$code").forEach {
            assertThrows(PairingException::class.java) { PairingPayload.parse(qr(it)) }
        }
        PairingPayload.parse(qr("http://100.64.0.1:8765/"))
        PairingPayload.parse(qr("http://[fd7a:115c:a1e0::1]:8765/"))
    }

    @Test fun `codes must encode exactly32 bytes canonically without padding`() {
        listOf("short", code + "=", "+".repeat(43), "A".repeat(42) + "B").forEach {
            assertThrows(PairingException::class.java) { PairingPayload.parse(qr().replace(code, it)) }
        }
        assertThrows(PairingException::class.java) { PairingPayload.parse(qr(name = code)) }
    }

    @Test fun `pairing response verifies service version credentials and UUID with redacted diagnostics`() {
        val result = PairingJson.result(response())
        assertEquals(token, result.token)
        assertFalse(result.toString().contains(token))
        listOf(
            response().replace("herdr-android-bridge", "different-service"),
            response().replace("\"protocol\":1", "\"protocol\":\"1\""),
            response().replace(token, "invalid-token"),
            response().replace("12345678-1234-4234-9234-123456789abc", "1-1-1-1-1"),
            response().replace("My server", token),
            response().dropLast(1) + ",\"extra\":true}",
        ).forEach {
            val error = assertThrows(PairingException::class.java) { PairingJson.result(it) }
            assertFalse(error.toString().contains(token))
            assertNull(error.cause)
        }
    }
}
