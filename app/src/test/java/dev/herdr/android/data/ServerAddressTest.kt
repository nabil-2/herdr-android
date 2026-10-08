package dev.herdr.android.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerAddressTest {
    @Test fun `HTTPS and explicit numeric private addresses are accepted`() {
        listOf(
            "https://bridge.example.com", "https://server.tail123.ts.net:8443/",
            "http://127.0.0.1:8080", "http://localhost:8080", "http://[::1]:8080",
            "http://100.64.0.1:8787", "http://100.127.255.254:8787",
            "http://[fd7a:115c:a1e0::1]:8787",
        ).forEach { ServerAddress.validate(it) }
    }

    @Test fun `cleartext hosts outside private exceptions cannot receive a bearer token`() {
        listOf(
            "http://example.com", "http://my-server.tail123.ts.net", "http://192.168.0.1",
            "http://10.0.0.1", "http://100.63.255.255", "http://100.128.0.1",
            "http://[fd7a:115c:a1e1::1]", "http://[fd00::1]", "http://localhost.example.com",
        ).forEach { address -> assertThrows(address, IllegalArgumentException::class.java) { ServerAddress.validate(address) } }
    }

    @Test fun `URL credentials queries fragments and API paths are rejected`() {
        listOf(
            "https://token:secret@bridge.example.com", "https://bridge.example.com?token=secret",
            "https://bridge.example.com#secret", "https://bridge.example.com/v1",
            "ftp://bridge.example.com", "", "not a server",
        ).forEach { address -> assertThrows(address, IllegalArgumentException::class.java) { ServerAddress.validate(address) } }
    }

    @Test fun `tokens cannot inject additional HTTP headers`() {
        assertEquals("abc-123_xyz", ServerAddress.validateToken(" abc-123_xyz "))
        listOf("", "token\r\nX-Injected: yes", "two words", "token\u0000", "tökén").forEach {
            assertThrows(IllegalArgumentException::class.java) { ServerAddress.validateToken(it) }
        }
    }
}
