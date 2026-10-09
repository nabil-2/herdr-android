package dev.herdr.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatLinksTest {
    @Test fun onlyWebAddressesWithoutEmbeddedCredentialsCanOpen() {
        assertEquals("https://example.com/docs#section", safeChatLink("https://example.com/docs#section"))
        assertEquals("http://example.com/", safeChatLink("http://example.com"))
        listOf("file:///tmp/secret", "javascript:alert(1)", "intent://app", "mailto:a@example.com", "/tmp/file", "https://owner:secret@example.com", "https://example.com/\nsecret").forEach {
            assertNull(it, safeChatLink(it))
        }
    }
}
