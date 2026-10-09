package dev.herdr.android.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class TabCreationProtocolTest {
    @Test fun `names and directories stay literal and kind is explicit`() {
        val body = Json.parseToJsonElement(TabCreationProtocol.input("w2", "  Mobile 雪 $()  ", "/tmp/space project", true)).jsonObject
        assertEquals(setOf("kind", "label", "cwd"), body.keys)
        assertEquals("codex", body["kind"]!!.jsonPrimitive.content)
        assertEquals("Mobile 雪 $()", body["label"]!!.jsonPrimitive.content)
        assertEquals("/tmp/space project", body["cwd"]!!.jsonPrimitive.content)
    }

    @Test fun `invalid selection controls and directory never become requests`() {
        listOf("w0", "w1/other", "w1:t1", "w1\n").forEach {
            assertThrows(IllegalArgumentException::class.java) { TabCreationProtocol.input(it, "Terminal", "/tmp", false) }
        }
        listOf("", " \t", "a\nb", "a\u007fb", "x".repeat(101)).forEach {
            assertThrows(IllegalArgumentException::class.java) { TabCreationProtocol.input("w1", it, "/tmp", false) }
        }
        listOf("", "relative", "/tmp\n", "/tmp\u0000", "/" + "x".repeat(4096)).forEach {
            assertThrows(IllegalArgumentException::class.java) { TabCreationProtocol.input("w1", "Terminal", it, false) }
        }
    }

    @Test fun `acknowledgement must stay in the requested workspace`() {
        val valid = """{"workspaceId":"w1","tabId":"w1:t2","paneId":"w1:p3","warning":"Open Terminal to approve directory access.","future":true}"""
        assertEquals("w1:p3", TabCreationProtocol.decode(valid, "w1").paneId)
        listOf(
            valid.replace("\"workspaceId\":\"w1\"", "\"workspaceId\":\"w2\""),
            valid.replace("w1:t2", "w2:t2"), valid.replace("w1:p3", "w2:p3"),
            valid.replace("w1:t2", "w1:t0"), valid.replace("w1:p3", "w1:p0"),
            valid.replace("w1:p3", "w1:p3/other"), "{}", "[]",
        ).forEach { assertThrows(IOException::class.java) { TabCreationProtocol.decode(it, "w1") } }
    }

    @Test fun `server errors do not retry a tab creation`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val client = HerdrClient(ServerCredentials(ServerAddress.validate(server.url("/").toString()), "synthetic-token"))
            try {
                server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", "0").setBody("""{"detail":"Herdr is unavailable"}"""))
                server.enqueue(MockResponse().setBody("""{"workspaceId":"w1","tabId":"w1:t2","paneId":"w1:p2"}"""))
                assertThrows(BridgeException::class.java) { runBlocking { client.createTab("w1", "Terminal", "/tmp", false) } }
                val request = server.takeRequest(1, TimeUnit.SECONDS)!!
                assertEquals("POST", request.method)
                assertEquals("/v1/workspaces/w1/tabs", request.path)
                assertEquals("Bearer synthetic-token", request.getHeader("Authorization"))
                assertEquals(1, server.requestCount)
            } finally { client.close() }
        }
    }
}
