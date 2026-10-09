package dev.herdr.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.HerdrViewModel
import dev.herdr.android.MainActivity
import dev.herdr.android.data.ConnectionState
import dev.herdr.android.data.PaneMode
import dev.herdr.android.data.ProfileStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Exercises dashboard actions, ViewModel selection, creation acknowledgement and first chat send. */
@RunWith(AndroidJUnit4::class)
class NewTabWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun createTerminalThenCodexInSelectedWorkspaceAndSendFirstMessage() {
        Fixture().use { fixture ->
            fixture.connect()
            compose.onNodeWithTag("new-tab").performClick()
            compose.onNodeWithTag("new-tab-name").performTextReplacement("Mobile 雪")
            compose.onNodeWithTag("new-tab-directory").performTextReplacement("/tmp/synthetic mobile")
            compose.onNodeWithTag("create-tab").performClick()
            compose.waitUntil(10_000) { fixture.creations.size == 1 && fixture.vm.state.value.creatingTab }
            compose.onNodeWithTag("create-tab").assertIsNotEnabled().performTouchInput { click() }
            assertEquals(1, fixture.creations.size)
            fixture.releaseCreation.countDown()
            compose.waitUntil(10_000) { fixture.vm.state.value.paneId == "w1:p2" && !fixture.vm.state.value.creatingTab }
            compose.runOnIdle {
                assertEquals(PaneMode.TERMINAL, fixture.vm.state.value.paneMode)
                assertEquals("w1:t2", fixture.vm.state.value.tabId)
                assertEquals("terminal", fixture.creations[0].getString("kind"))
                assertEquals("Mobile 雪", fixture.creations[0].getString("label"))
                assertEquals("/tmp/synthetic mobile", fixture.creations[0].getString("cwd"))
                fixture.vm.leavePane()
            }
            compose.onNodeWithTag("new-codex-chat").performClick()
            compose.onNodeWithTag("create-tab").performClick()
            compose.waitUntil(10_000) {
                val state = fixture.vm.state.value
                state.paneId == "w1:p3" && !state.creatingTab && state.paneMode == PaneMode.CHAT && state.chat.canSend
            }
            compose.onNodeWithTag("chat-screen").assertIsDisplayed()
            compose.onNodeWithTag("chat-draft").performTextInput("My first mobile message. 雪")
            compose.onNodeWithTag("send-chat").performClick()
            compose.waitUntil(10_000) { fixture.messages.size == 1 && fixture.vm.state.value.chat.draft.isEmpty() }
            compose.runOnIdle {
                assertEquals(2, fixture.creations.size)
                assertEquals("codex", fixture.creations[1].getString("kind"))
                assertEquals("/tmp/synthetic-project", fixture.creations[1].getString("cwd"))
                assertEquals("My first mobile message. 雪", fixture.messages.single().getString("text"))
                assertEquals(2L, fixture.vm.state.value.creationRevision)
                assertEquals(0, fixture.unexpectedWrites.get())
            }
        }
    }

    private inner class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val profiles = ProfileStore(context)
        private val originalIds = profiles.profiles().map { it.id }.toSet()
        private val server = MockWebServer()
        val creations = CopyOnWriteArrayList<JSONObject>()
        val messages = CopyOnWriteArrayList<JSONObject>()
        val unexpectedWrites = AtomicInteger()
        val releaseCreation = CountDownLatch(1)
        lateinit var vm: HerdrViewModel
        private val baseUrl: String

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer $TOKEN") return MockResponse().setResponseCode(401)
                    return when (request.requestUrl!!.encodedPath) {
                        "/v1/health" -> json("""{"service":"herdr-android-bridge","protocol":1,"capabilities":["workspace-tabs","codex-create","codex-chat","codex-chat-working-input"]}""")
                        "/v1/snapshot" -> json(snapshot())
                        "/v1/workspaces/w1/tabs" -> {
                            assertEquals("POST", request.method)
                            val input = JSONObject(request.body.readUtf8())
                            creations.add(input)
                            val number = creations.size + 1
                            if (number == 2) assertTrue("First creation must be explicitly released", releaseCreation.await(15, TimeUnit.SECONDS))
                            json("""{"workspaceId":"w1","tabId":"w1:t$number","paneId":"w1:p$number"}""").setResponseCode(201)
                        }
                        "/v1/panes/w1:p3/conversation" -> {
                            assertEquals("1", request.getHeader("X-Herdr-Chat-Fresh-Input"))
                            json("""{"binding":"synthetic-new-chat-binding","revision":"r${messages.size}","historyEpoch":"new-chat-history","messages":[],"unchanged":false,"canSend":true,"status":"unknown","freshBootstrap":true}""")
                        }
                        "/v1/panes/w1:p3/messages" -> {
                            assertEquals("1", request.getHeader("X-Herdr-Chat-Fresh-Input"))
                            messages.add(JSONObject(request.body.readUtf8()))
                            json("""{"ok":true}""")
                        }
                        else -> {
                            if (request.method == "POST") unexpectedWrites.incrementAndGet()
                            MockResponse().setResponseCode(404)
                        }
                    }
                }
            }
            server.start()
            baseUrl = server.url("/").toString()
        }

        private fun snapshot(): String {
            val tabs = JSONArray().put(JSONObject("""{"id":"w1:t1","workspaceId":"w1","label":"main"}"""))
            val panes = JSONArray()
            creations.forEachIndexed { index, input ->
                val number = index + 2
                tabs.put(JSONObject().put("id", "w1:t$number").put("workspaceId", "w1").put("label", input.getString("label")))
                val pane = JSONObject().put("id", "w1:p$number").put("workspaceId", "w1").put("tabId", "w1:t$number")
                    .put("title", input.getString("label")).put("cwd", input.getString("cwd")).put("status", "idle")
                if (input.getString("kind") == "codex") pane.put("agent", "codex")
                    .put("conversation", JSONObject().put("provider", "codex").put("available", true))
                panes.put(pane)
            }
            return JSONObject().put("herdrVersion", "0.9.3")
                .put("workspaces", JSONArray("""[{"id":"w1","label":"Mobile project","cwd":"/tmp/synthetic-project"},{"id":"w2","label":"Other project","cwd":"/tmp/other"}]"""))
                .put("tabs", tabs).put("panes", panes).toString()
        }

        fun connect() {
            compose.onNodeWithContentDescription("Add server").performClick()
            compose.onNode(hasSetTextAction() and hasText("Server name")).performTextInput("Mobile creation fixture")
            compose.onNode(hasSetTextAction() and hasText("Bridge address")).performTextInput(baseUrl)
            compose.onNode(hasSetTextAction() and hasText("Access token")).performTextInput(TOKEN)
            compose.onNodeWithText("Save & connect").performClick()
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[HerdrViewModel::class.java] }
            compose.waitUntil(10_000) { vm.state.value.connection == ConnectionState.CONNECTED && vm.state.value.workspaceId == "w1" }
            assertTrue(creations.isEmpty())
        }

        override fun close() {
            releaseCreation.countDown()
            compose.runOnIdle { ViewModelProvider(compose.activity)[HerdrViewModel::class.java].disconnect() }
            profiles.profiles().filter {
                it.id !in originalIds && it.baseUrl.removeSuffix("/") == baseUrl.removeSuffix("/")
            }.forEach { profiles.remove(it.id) }
            server.shutdown()
        }
    }

    private companion object {
        const val TOKEN = "native-mobile-creation-isolated-test-token"
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
}
