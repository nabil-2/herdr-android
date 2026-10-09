package dev.herdr.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.HerdrViewModel
import dev.herdr.android.MainActivity
import dev.herdr.android.data.ConnectionState
import dev.herdr.android.data.ProfileStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Actual screen, protocol parsing and ViewModel writes against synthetic loopback data. */
@RunWith(AndroidJUnit4::class)
class NativeChatSendDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun workingCodexAcceptsLiteralUpdatesOnceAndKeepsAcceptingFollowUpsAfterAcknowledgement() {
        Fixture().use { fixture ->
            fixture.openWorkingConversation()
            val first = "Keep **this literal** and the scope small.\nUse \"quoted text\", a \\ path, and 雪."
            val followUp = "Also keep the existing tests."
            val revision = fixture.vm.state.value.chat.ackRevision

            compose.onNodeWithTag("chat-draft").performTextInput(first)
            compose.onNodeWithTag("send-chat").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { fixture.messages.size == 1 && fixture.vm.state.value.chat.sending }
            compose.onNodeWithTag("chat-draft").assertTextEquals(first)
            compose.onNodeWithTag("send-chat").assertIsNotEnabled()
            // Touch the actual disabled control while its first HTTP acknowledgement is held.
            compose.onNodeWithTag("send-chat").performTouchInput { click() }
            compose.runOnIdle {
                assertEquals(1, fixture.messages.size)
                assertEquals(revision, fixture.vm.state.value.chat.ackRevision)
                assertFalse(fixture.vm.state.value.chat.deliveryUncertain)
            }
            fixture.assertMessage(0, first)

            val readsBeforeAcknowledgement = fixture.conversationReads.get()
            fixture.releaseFirstAcknowledgement.countDown()
            compose.waitUntil(10_000) {
                val chat = fixture.vm.state.value.chat
                chat.ackRevision == revision + 1 && chat.draft.isEmpty() && !chat.sending &&
                    chat.status == "working" && chat.canSend && fixture.conversationReads.get() > readsBeforeAcknowledgement
            }
            assertEmptyDraft()
            compose.onNodeWithTag("chat-working").assertTextEquals("Working · Send updates this task")
            compose.onNodeWithTag("send-chat").assertIsNotEnabled()

            // The authoritative refresh still says working. A second update must reach
            // the server through all three protocol, ViewModel and composer gates.
            compose.onNodeWithTag("chat-draft").performTextInput(followUp)
            compose.onNodeWithTag("send-chat").assertIsEnabled().performClick()
            compose.waitUntil(10_000) {
                val chat = fixture.vm.state.value.chat
                fixture.messages.size == 2 && chat.ackRevision == revision + 2 &&
                    chat.draft.isEmpty() && !chat.sending && chat.canSend && chat.status == "working"
            }
            fixture.assertMessage(1, followUp)
            assertEmptyDraft()
            compose.runOnIdle {
                assertEquals(listOf(first, followUp), fixture.messages.map { JSONObject(it.body).getString("text") })
                assertTrue(fixture.conversationHeaders.isNotEmpty())
                assertTrue(fixture.conversationHeaders.all { it == "1" })
                assertEquals(0, fixture.unexpectedWrites.get())
                assertEquals(null, fixture.vm.state.value.chat.error)
                assertFalse(fixture.vm.state.value.chat.deliveryUncertain)
            }
        }
    }

    private fun assertEmptyDraft() {
        compose.onNodeWithTag("chat-draft").assert(SemanticsMatcher("Empty chat draft") { node ->
            SemanticsProperties.EditableText in node.config && node.config[SemanticsProperties.EditableText].text.isEmpty()
        })
    }

    private data class MessageRequest(val path: String, val method: String?, val token: String?, val workingInput: String?, val body: String)

    private inner class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val profiles = ProfileStore(context)
        private val originalProfileIds = profiles.profiles().map { it.id }.toSet()
        private val server = MockWebServer()
        private val acknowledgements = AtomicInteger()
        val messages = CopyOnWriteArrayList<MessageRequest>()
        val conversationReads = AtomicInteger()
        val conversationHeaders = CopyOnWriteArrayList<String?>()
        val unexpectedWrites = AtomicInteger()
        val releaseFirstAcknowledgement = CountDownLatch(1)
        val baseUrl: String
        lateinit var vm: HerdrViewModel

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    if (request.getHeader("Authorization") != "Bearer $TOKEN") return MockResponse().setResponseCode(401)
                    return when (path) {
                        "/v1/health" -> json("""{"service":"herdr-android-bridge","protocol":1,"herdrVersion":"0.9.3","capabilities":["codex-chat","codex-chat-working-input"]}""")
                        "/v1/snapshot" -> json("""{"herdrVersion":"0.9.3","workspaces":[{"id":"w1","label":"Working chat fixture","cwd":"/tmp/synthetic-chat"}],"tabs":[{"id":"w1:t1","workspaceId":"w1","label":"main"}],"panes":[{"id":"w1:p1","workspaceId":"w1","tabId":"w1:t1","title":"Synthetic working Codex","cwd":"/tmp/synthetic-chat","agent":"codex","status":"working","conversation":{"provider":"codex","available":true}}]}""")
                        "/v1/panes/w1:p1/conversation" -> {
                            conversationReads.incrementAndGet()
                            conversationHeaders.add(request.getHeader(WORKING_INPUT_HEADER))
                            json("""{"binding":"$BINDING","revision":"r${acknowledgements.get()}","historyEpoch":"synthetic-history","messages":[{"id":"a1","role":"assistant","text":"I am working on the synthetic task.","phase":"commentary"}],"olderCursor":null,"unchanged":false,"canSend":true,"status":"working"}""")
                        }
                        "/v1/panes/w1:p1/messages" -> {
                            messages.add(MessageRequest(path, request.method, request.getHeader("Authorization"),
                                request.getHeader(WORKING_INPUT_HEADER), request.body.readUtf8()))
                            if (messages.size == 1 && !releaseFirstAcknowledgement.await(15, TimeUnit.SECONDS)) {
                                MockResponse().setResponseCode(503)
                            } else {
                                acknowledgements.incrementAndGet()
                                json("""{"ok":true}""")
                            }
                        }
                        else -> {
                            if (request.method == "POST") unexpectedWrites.incrementAndGet()
                            MockResponse().setResponseCode(404)
                        }
                    }
                }
            }
            server.start()
            // Resolve the bound loopback address on the instrumentation thread.
            baseUrl = server.url("/").toString()
        }

        fun openWorkingConversation() {
            compose.onNodeWithContentDescription("Add server").performClick()
            compose.onNode(hasSetTextAction() and hasText("Server name")).performTextInput("Working send fixture")
            compose.onNode(hasSetTextAction() and hasText("Bridge address")).performTextInput(baseUrl)
            compose.onNode(hasSetTextAction() and hasText("Access token")).performTextInput(TOKEN)
            compose.onNodeWithText("Save & connect").performClick()
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[HerdrViewModel::class.java] }
            compose.waitUntil(10_000) {
                vm.state.value.connection == ConnectionState.CONNECTED &&
                    compose.onAllNodesWithContentDescription("Open conversation").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Open conversation").performClick()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithTag("chat-screen").fetchSemanticsNodes().isNotEmpty() &&
                    vm.state.value.chat.bindingId == BINDING && !vm.state.value.chat.loading &&
                    vm.state.value.chat.canSend && vm.state.value.chat.status == "working"
            }
            compose.onNodeWithTag("chat-screen").assertIsDisplayed()
            compose.onNodeWithTag("chat-working").assertTextEquals("Working · Send updates this task")
            compose.runOnIdle { assertTrue(messages.isEmpty()) }
        }

        fun assertMessage(index: Int, text: String) {
            val request = messages[index]
            assertEquals("POST", request.method)
            assertEquals("/v1/panes/w1:p1/messages", request.path)
            assertEquals("Bearer $TOKEN", request.token)
            assertEquals("1", request.workingInput)
            val body = JSONObject(request.body)
            assertEquals(setOf("binding", "text"), body.keys().asSequence().toSet())
            assertEquals(BINDING, body.getString("binding"))
            assertEquals(text, body.getString("text"))
        }

        override fun close() {
            releaseFirstAcknowledgement.countDown()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { ViewModelProvider(compose.activity)[HerdrViewModel::class.java].disconnect() }
            profiles.profiles().filterNot { it.id in originalProfileIds }.forEach { profiles.remove(it.id) }
            server.shutdown()
        }
    }

    private companion object {
        const val TOKEN = "native-working-chat-public-isolated-test-token"
        const val BINDING = "synthetic-working-chat-binding"
        const val WORKING_INPUT_HEADER = "X-Herdr-Chat-Working-Input"
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
}
