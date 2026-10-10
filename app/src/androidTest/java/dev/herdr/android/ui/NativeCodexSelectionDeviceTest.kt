package dev.herdr.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises an unattributed shared-daemon tab with no real agents or model calls. */
@RunWith(AndroidJUnit4::class)
class NativeCodexSelectionDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun sharedTabRequiresDeliberateChoiceThenUsesTheChosenChatBinding() {
        Fixture().use { fixture ->
            fixture.open()
            compose.onNodeWithText("Choose a conversation").assertIsDisplayed()
            compose.onNodeWithText("Shared synthetic chat").assertIsDisplayed()
            assertTrue(fixture.selections.isEmpty())
            assertTrue(fixture.messages.isEmpty())
            compose.onNodeWithTag("codex-conversation-$ID").performClick()
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.bindingId == BINDING && fixture.vm.state.value.chat.canSend }
            val selection = JSONObject(fixture.selections.single())
            assertEquals(ID, selection.getString("id"))
            assertEquals(TOKEN, selection.getString("selectionToken"))
            compose.onNodeWithTag("codex-conversation-selector").assertDoesNotExist()
            compose.onNodeWithTag("chat-draft").performTextInput("Synthetic reviewed follow-up")
            compose.onNodeWithTag("send-chat").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.ackRevision == 1L }
            assertEquals(BINDING, JSONObject(fixture.messages.single()).getString("binding"))
            assertEquals("Synthetic reviewed follow-up", JSONObject(fixture.messages.single()).getString("text"))
            assertTrue(fixture.unexpectedWrites.isEmpty())
        }
    }

    @Test fun expiredSelectionKeepsChooserOpenAndDoesNotSendOrGuessAnotherThread() {
        Fixture(rejectSelection = true).use { fixture ->
            fixture.open()
            compose.onNodeWithTag("codex-conversation-$ID").performClick()
            compose.waitUntil(10_000) { fixture.vm.state.value.codexSelection.error != null }
            compose.onNodeWithTag("codex-selection-error").assertIsDisplayed()
            compose.onNodeWithTag("codex-conversation-selector").assertIsDisplayed()
            compose.onNodeWithTag("send-chat").assertDoesNotExist()
            compose.runOnIdle { fixture.vm.sendChat() }
            assertTrue(fixture.messages.isEmpty())
            assertEquals(1, fixture.selections.size)
            compose.onNodeWithTag("refresh-codex-conversations").performClick()
            compose.waitUntil(10_000) { !fixture.vm.state.value.codexSelection.loading && fixture.vm.state.value.codexSelection.error == null }
            assertEquals(1, fixture.selections.size)
            assertTrue(fixture.unexpectedWrites.isEmpty())
        }
    }

    private inner class Fixture(private val rejectSelection: Boolean = false) : AutoCloseable {
        private val profiles = ProfileStore(InstrumentationRegistry.getInstrumentation().targetContext)
        private val originalProfiles = profiles.profiles().map { it.id }.toSet()
        private val selected = AtomicBoolean(false)
        val selections = CopyOnWriteArrayList<String>()
        val messages = CopyOnWriteArrayList<String>()
        val unexpectedWrites = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        lateinit var vm: HerdrViewModel
        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    return when (path) {
                        "/v1/health" -> json("""{"service":"herdr-android-bridge","protocol":1,"capabilities":["codex-chat","codex-chat-selection"]}""")
                        "/v1/snapshot" -> json("""{"workspaces":[{"id":"w1","label":"Selection fixture","cwd":"/tmp/synthetic"}],"tabs":[{"id":"w1:t1","workspaceId":"w1","label":"main"}],"panes":[{"id":"w1:p1","workspaceId":"w1","tabId":"w1:t1","title":"Synthetic Codex","cwd":"/tmp/synthetic","agent":"codex","status":"working","conversation":{"provider":"codex","available":${selected.get()},"selectable":true}}]}""")
                        "/v1/panes/w1:p1/codex-conversations" -> json("""{"conversations":[{"id":"$ID","title":"Shared synthetic chat","createdAt":1700000000,"status":"working"}],"selectionToken":"$TOKEN"}""")
                        "/v1/panes/w1:p1/codex-conversation" -> {
                            selections += request.body.readUtf8()
                            if (rejectSelection) json("""{"detail":"The conversation list expired. Refresh before choosing a chat."}""").setResponseCode(409)
                            else { selected.set(true); json("""{"ok":true}""") }
                        }
                        "/v1/panes/w1:p1/conversation" -> {
                            if (!selected.get()) json("""{"detail":"Choose a conversation first."}""").setResponseCode(409)
                            else json("""{"binding":"$BINDING","revision":"synthetic","historyEpoch":"synthetic","messages":[{"id":"a1","role":"assistant","text":"Chosen synthetic conversation."}],"unchanged":false,"canSend":true,"status":"working"}""")
                        }
                        "/v1/panes/w1:p1/messages" -> { messages += request.body.readUtf8(); json("""{"ok":true}""") }
                        else -> { if (request.method != "GET") unexpectedWrites += path; MockResponse().setResponseCode(404) }
                    }
                }
            }
            server.start()
        }
        fun open() {
            compose.onNodeWithContentDescription("Add server").performClick()
            compose.onNode(hasSetTextAction() and hasText("Server name")).performTextInput("Synthetic selection server")
            compose.onNode(hasSetTextAction() and hasText("Bridge address")).performTextInput(server.url("/").toString())
            compose.onNode(hasSetTextAction() and hasText("Access token")).performTextInput("native-selection-synthetic-token")
            compose.onNodeWithText("Save & connect").performClick()
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[HerdrViewModel::class.java] }
            compose.waitUntil(10_000) { vm.state.value.connection == ConnectionState.CONNECTED && compose.onAllNodesWithContentDescription("Open conversation").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Open conversation").performClick()
            compose.waitUntil(10_000) { !vm.state.value.codexSelection.loading && vm.state.value.codexSelection.choices.isNotEmpty() }
        }
        override fun close() {
            compose.runOnIdle { ViewModelProvider(compose.activity)[HerdrViewModel::class.java].disconnect() }
            profiles.profiles().filterNot { it.id in originalProfiles }.forEach { profiles.remove(it.id) }
            server.shutdown()
        }
    }
    private companion object {
        const val ID = "00000000-0000-4000-8000-000000000001"
        const val TOKEN = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val BINDING = "synthetic-selected-thread-binding"
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
}
