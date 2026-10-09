package dev.herdr.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.HerdrViewModel
import dev.herdr.android.MainActivity
import dev.herdr.android.data.ProfileStore
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Real navigation/ViewModel lifecycle against a disposable loopback server. */
@RunWith(AndroidJUnit4::class)
class NativeChatNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun identifiedCodexOpensChatAndHiddenReadersStopPollingWhileTheDraftSurvives() {
        val reads = AtomicInteger()
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    path == "/v1/health" -> json("""{"service":"herdr-android-bridge","protocol":1,"herdrVersion":"0.9.3","capabilities":["codex-chat"]}""")
                    path == "/v1/snapshot" -> json("""{"herdrVersion":"0.9.3","workspaces":[{"id":"w1","label":"Chat fixture","cwd":"/tmp"}],"tabs":[{"id":"w1:t1","workspaceId":"w1","label":"main"}],"panes":[{"id":"w1:p1","workspaceId":"w1","tabId":"w1:t1","title":"Codex","cwd":"/tmp","agent":"codex","status":"idle","conversation":{"provider":"codex","available":true}}]}""")
                    path.endsWith("/conversation") -> {
                        reads.incrementAndGet()
                        json("""{"binding":"test-binding","revision":"r1","historyEpoch":"epoch1","messages":[{"id":"u1","role":"user","text":"Show me how the phone connects to my Codex session."},{"id":"a1","role":"assistant","text":"## Ready to work\n\n- Your conversation stays with the Codex session on your computer.\n- Open Terminal when an approval needs attention.\n\n```kotlin\nval session = \"Codex\"\nprintln(\"Hello from Herdr Pocket\")\n```","phase":"final_answer"}],"olderCursor":null,"unchanged":false,"canSend":true,"status":"idle"}""")
                    }
                    path.endsWith("/stream") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                            webSocket.send("""{"type":"terminal.frame","bytes":"VGVybWluYWwgZmFsbGJhY2s=","encoding":"ansi","full":true,"width":80,"height":30,"seq":1}""")
                        }
                    })
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profiles = ProfileStore(context)
        val originalIds = profiles.profiles().map { it.id }.toSet()
        try {
            compose.onNodeWithContentDescription("Add server").performClick()
            compose.onNode(hasSetTextAction() and hasText("Server name")).performTextInput("Native chat fixture")
            compose.onNode(hasSetTextAction() and hasText("Bridge address")).performTextInput(server.url("/").toString())
            compose.onNode(hasSetTextAction() and hasText("Access token")).performTextInput("native-chat-public-isolated-test-token")
            compose.onNodeWithText("Save & connect").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Open conversation").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Open conversation").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat-screen").fetchSemanticsNodes().isNotEmpty() && reads.get() >= 2 }
            compose.onNodeWithTag("chat-screen").assertIsDisplayed()
            compose.onNodeWithTag("key-enter").assertDoesNotExist()
            compose.waitUntil(5_000) { compose.onAllNodesWithText("Copy code").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Copy code").assertIsDisplayed()
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250L, 5_000L)
            val screenshots = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
            File(screenshots, "chat.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("chat-draft").performTextInput("Draft that stays in memory")
            compose.onNodeWithTag("open-chat-terminal").performClick()
            compose.onNodeWithTag("return-to-chat").assertIsDisplayed()
            compose.waitForIdle()
            Thread.sleep(250)
            val terminalReads = reads.get()
            Thread.sleep(1_300)
            assertEquals("Terminal mode must stop conversation polling", terminalReads, reads.get())
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitUntil(10_000) { reads.get() > terminalReads }
            compose.onNodeWithTag("chat-draft").assertTextEquals("Draft that stays in memory")

            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat-screen").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("chat-draft").assertTextEquals("Draft that stays in memory")
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            Thread.sleep(250)
            val backgroundReads = reads.get()
            Thread.sleep(1_300)
            assertEquals("Backgrounding must stop conversation polling", backgroundReads, reads.get())
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { reads.get() > backgroundReads }
            assertTrue(reads.get() > 2)
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { ViewModelProvider(compose.activity)[HerdrViewModel::class.java].disconnect() }
            profiles.profiles().filterNot { it.id in originalIds }.forEach { profiles.remove(it.id) }
            server.shutdown()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
