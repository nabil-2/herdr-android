package dev.herdr.android.data

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

object ServerAddress {
    /** No DNS lookup is used to grant a cleartext exception. */
    fun validate(value: String): HttpUrl {
        val input = value.trim()
        require(input.isNotEmpty()) { "Enter the bridge server URL." }
        val url = input.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Enter a valid HTTPS server URL.")
        require(url.username.isEmpty() && url.password.isEmpty()) { "Credentials belong in the token field, not the URL." }
        require(url.query == null && url.fragment == null) { "Use the server URL without query parameters or fragments." }
        require(url.encodedPath == "/") { "Use the server URL without an API path." }
        require(url.isHttps || allowsPrivateHttp(url.host)) {
            "HTTPS is required. HTTP is allowed only for loopback or a numeric Tailscale address."
        }
        return url
    }

    fun validateToken(value: String): String {
        val token = value.trim()
        require(token.isNotEmpty()) { "Enter the bridge access token." }
        require(token.length <= 4096 && token.none { it.isWhitespace() || it.code < 33 || it.code > 126 }) {
            "The token contains invalid characters."
        }
        return token
    }

    private fun allowsPrivateHttp(host: String): Boolean {
        if (host == "localhost") return true
        val components = host.split('.')
        if (components.size == 4 && components.all { it.isNotEmpty() && it.all(Char::isDigit) }) {
            val octets = components.map { it.toIntOrNull() ?: return false }
            if (octets.any { it !in 0..255 }) return false
            return octets[0] == 127 || (octets[0] == 100 && octets[1] in 64..127)
        }
        // Only an already parsed, literal IPv6 host reaches InetAddress, never a name.
        if (!host.contains(':') || host.any { it !in "0123456789abcdefABCDEF:" }) return false
        val bytes = runCatching { InetAddress.getByName(host).address }.getOrNull() ?: return false
        if (bytes.size != 16) return false
        if (bytes.take(15).all { it == 0.toByte() } && bytes[15] == 1.toByte()) return true
        val tailscalePrefix = byteArrayOf(0xfd.toByte(), 0x7a, 0x11, 0x5c, 0xa1.toByte(), 0xe0.toByte())
        return bytes.take(6).toByteArray().contentEquals(tailscalePrefix)
    }
}
