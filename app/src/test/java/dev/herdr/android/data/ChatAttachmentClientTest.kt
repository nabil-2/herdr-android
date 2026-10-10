package dev.herdr.android.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class ChatAttachmentClientTest {
    private val id = "a".repeat(32)

    @Test fun `upload streams exact bytes and percent encodes reviewed name without leaking binding into URL`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val file = File.createTempFile("native-chat-fixture", ".bin")
            val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
            file.writeBytes(bytes)
            val name = "Review 雪 & photo.png"
            server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"$id","name":"$name","kind":"image","mimeType":"image/png","sizeBytes":${bytes.size}}"""))
            val client = HerdrClient(ServerCredentials(server.url("/"), "synthetic-token"))
            try {
                assertEquals(id, client.uploadChatAttachment("w:p", "private-binding", name, ChatAttachmentKind.IMAGE, file).id)
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("POST", request.method)
                assertEquals("/v1/panes/w:p/attachments", request.requestUrl!!.encodedPath)
                assertEquals(name, request.requestUrl!!.queryParameter("name"))
                assertEquals("image", request.requestUrl!!.queryParameter("kind"))
                assertEquals("Bearer synthetic-token", request.getHeader("Authorization"))
                assertEquals("private-binding", request.getHeader("X-Herdr-Chat-Binding"))
                assertEquals("application/octet-stream", request.getHeader("Content-Type"))
                assertFalse(request.path!!.contains("private-binding"))
                assertArrayEquals(bytes, request.body.readByteArray())
            } finally { client.close(); file.delete() }
        }
    }

    @Test fun `upload server retry advice cannot cause a duplicate upload`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val file = File.createTempFile("native-chat-fixture", ".txt").apply { writeText("Synthetic file") }
            val client = HerdrClient(ServerCredentials(server.url("/"), "synthetic-token"))
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(201).setBody("{}"))
            try {
                assertThrows(BridgeException::class.java) { runBlocking { client.uploadChatAttachment("w:p", "binding", "note.txt", ChatAttachmentKind.FILE, file) } }
                assertEquals("POST", server.takeRequest(1, TimeUnit.SECONDS)!!.method)
                assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            } finally { client.close(); file.delete() }
        }
    }

    @Test fun `delete and explicit uncertainty acknowledgement retain exact binding and method`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = HerdrClient(ServerCredentials(server.url("/"), "synthetic-token"))
            repeat(2) { server.enqueue(MockResponse().setBody("""{"ok":true}""")) }
            try {
                client.deleteChatAttachment("w:p", "binding", id)
                client.acknowledgeChatAttachments("w:p", "binding")
                val deletion = server.takeRequest(1, TimeUnit.SECONDS)!!
                val ack = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("DELETE", deletion.method)
                assertEquals("/v1/panes/w:p/attachments/$id", deletion.path)
                assertEquals("POST", ack.method)
                assertEquals("/v1/panes/w:p/attachments/acknowledge", ack.path)
                assertEquals(0L, ack.bodySize)
                listOf(deletion, ack).forEach { assertEquals("binding", it.getHeader("X-Herdr-Chat-Binding")) }
            } finally { client.close() }
        }
    }

    @Test fun `partial attachment send response cannot replay consumed receipts despite retry advice`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = HerdrClient(ServerCredentials(server.url("/"), "synthetic-token"))
            server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0").setBody("""{"detail":"Check Terminal"}"""))
            server.enqueue(MockResponse().setBody("""{"ok":true}"""))
            try {
                assertThrows(BridgeException::class.java) { runBlocking { client.sendMessage("w:p", "binding", "", listOf(id)) } }
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("/v1/panes/w:p/messages", request.path)
                assertTrue(request.body.readUtf8().contains(id))
                assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            } finally { client.close() }
        }
    }
}
