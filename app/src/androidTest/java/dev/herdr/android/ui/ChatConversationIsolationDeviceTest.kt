package dev.herdr.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
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
import dev.herdr.android.data.TabCreationProtocol
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Two disposable chat identities exercise the real activity, creation and poll lifecycle. */
@RunWith(AndroidJUnit4::class)
class ChatConversationIsolationDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun creatingAnotherChatKeepsTheOriginalHistoryAndRequiresConfirmationForAnUnexpectedRebind() {
        Fixture().use { fixture ->
            fixture.openOld()
            compose.onNodeWithTag("chat-draft").performTextInput("Reply for the original chat")
            compose.onNodeWithContentDescription("Back to workspaces").performClick()
            compose.onNodeWithTag("new-codex-chat").performClick()
            compose.onNodeWithTag("create-tab").performClick()
            compose.waitUntil(10_000) { fixture.vm.state.value.paneId == NEW_PANE && fixture.vm.state.value.chat.conversationId == NEW_ID }
            awaitText(NEW_TEXT)
            compose.onNodeWithTag("chat-draft").performTextInput("Draft for the new pane")
            compose.onNodeWithContentDescription("Back to workspaces").performClick()
            compose.onNodeWithText("Original").performClick()
            compose.onNodeWithContentDescription("Open conversation").performClick()
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.conversationId == OLD_ID && fixture.vm.state.value.chat.canSend }
            awaitText(OLD_TEXT)
            compose.onNodeWithText(NEW_TEXT).assertDoesNotExist()
            compose.onNodeWithTag("chat-draft").assertTextEquals("Reply for the original chat")
            assertEquals(1, fixture.creations.get())

            // Reproduce a host routing the new thread into the original pane.
            fixture.misrouteOld.set(true)
            compose.runOnIdle { fixture.vm.refreshChat() }
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.conversationChanged }
            awaitText(OLD_TEXT)
            compose.onNodeWithText(NEW_TEXT).assertDoesNotExist()
            compose.onNodeWithTag("chat-conversation-changed").assertIsDisplayed()
            compose.onNodeWithTag("send-chat").assertIsNotEnabled()
            compose.runOnIdle { fixture.vm.sendChat() }
            assertTrue(fixture.messages.isEmpty())
            assertEquals(OLD_ID, fixture.vm.state.value.chat.conversationId)
            compose.onNodeWithText("Show current conversation").performClick()
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.conversationId == NEW_ID && !fixture.vm.state.value.chat.conversationChanged }
            awaitText(NEW_TEXT)
            assertEquals("", fixture.vm.state.value.chat.draft)
            assertTrue(fixture.unexpectedWrites.isEmpty())
        }
    }

    @Test fun rotatingTheBindingForTheSameConversationKeepsItsOriginalDraftAndUsesTheNewBinding() {
        Fixture().use { fixture ->
            fixture.openOld()
            compose.onNodeWithTag("chat-draft").performTextInput("Reviewed reply for the same conversation")
            fixture.rotateOldBinding.set(true)
            compose.runOnIdle { fixture.vm.refreshChat() }
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.bindingId == ROTATED_BINDING && fixture.vm.state.value.chat.canSend }
            assertEquals(OLD_ID, fixture.vm.state.value.chat.conversationId)
            assertFalse(fixture.vm.state.value.chat.conversationChanged)
            awaitText(OLD_TEXT)
            compose.onNodeWithTag("chat-draft").assertTextEquals("Reviewed reply for the same conversation")
            compose.onNodeWithTag("send-chat").performClick()
            compose.waitUntil(10_000) { fixture.messages.size == 1 && fixture.vm.state.value.chat.ackRevision == 1L }
            assertEquals(ROTATED_BINDING, fixture.messages.single().getString("binding"))
            assertEquals("Reviewed reply for the same conversation", fixture.messages.single().getString("text"))
        }
    }

    @Test fun aLegacyCompanionCannotStartAnUnverifiedSharedCodexSession() {
        Fixture(isolatedCreation = false).use { fixture ->
            fixture.connect()
            compose.onNodeWithTag("new-codex-chat").performClick()
            compose.onNodeWithTag("create-tab").assertIsNotEnabled()
            compose.onNodeWithText(TabCreationProtocol.CODEX_CREATION_REQUIREMENT).assertIsDisplayed()
            compose.runOnIdle { fixture.vm.createTab("w1", "Synthetic chat", "/tmp/synthetic", codex = true) }
            assertEquals(0, fixture.creations.get())
            assertTrue(fixture.unexpectedWrites.isEmpty())
        }
    }

    @Test fun anInspectedSwitchPreservesTheOldUncertaintyAndNeverAcknowledgesAnotherConversationsMarker() {
        Fixture().use { fixture ->
            fixture.openOld()
            compose.onNodeWithTag("chat-draft").performTextInput("Unconfirmed original reply")
            fixture.oldUncertain.set(true)
            compose.runOnIdle { fixture.vm.refreshChat() }
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.attachmentDeliveryUncertain }
            fixture.newUncertain.set(true)
            fixture.misrouteOld.set(true)
            compose.runOnIdle { fixture.vm.refreshChat() }
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.conversationChanged }
            awaitText(OLD_TEXT)
            compose.onNodeWithText("I checked Terminal; switch chats").performClick()
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.conversationId == NEW_ID && !fixture.vm.state.value.chat.conversationChanged }
            assertTrue("The new conversation's authoritative guard must survive the switch", fixture.vm.state.value.chat.attachmentDeliveryUncertain)
            assertFalse(fixture.vm.state.value.chat.canSubmitMessage())
            assertEquals(0, fixture.acknowledgements.get())
            assertTrue(fixture.messages.isEmpty())
            assertEquals("", fixture.vm.state.value.chat.draft)
            assertTrue(fixture.unexpectedWrites.isEmpty())
        }
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private inner class Fixture(private val isolatedCreation: Boolean = true) : AutoCloseable {
        private val profiles = ProfileStore(InstrumentationRegistry.getInstrumentation().targetContext)
        private val originalProfiles = profiles.profiles().map { it.id }.toSet()
        val server = MockWebServer()
        val creations = AtomicInteger()
        val misrouteOld = AtomicBoolean()
        val rotateOldBinding = AtomicBoolean()
        val oldUncertain = AtomicBoolean()
        val newUncertain = AtomicBoolean()
        val acknowledgements = AtomicInteger()
        val messages = CopyOnWriteArrayList<JSONObject>()
        val unexpectedWrites = CopyOnWriteArrayList<String>()
        lateinit var vm: HerdrViewModel

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer $TOKEN") return MockResponse().setResponseCode(401)
                    val path = request.requestUrl!!.encodedPath
                    return when (path) {
                        "/v1/health" -> {
                            val caps = JSONArray(listOf("codex-chat", "codex-create", "workspace-tabs"))
                            if (isolatedCreation) caps.put("codex-create-isolated")
                            json(JSONObject().put("service", "herdr-android-bridge").put("protocol", 1).put("capabilities", caps).toString())
                        }
                        "/v1/snapshot" -> json(snapshot())
                        "/v1/workspaces/w1/tabs" -> {
                            assertEquals("POST", request.method)
                            assertEquals("codex", JSONObject(request.body.readUtf8()).getString("kind"))
                            creations.incrementAndGet()
                            json("""{"workspaceId":"w1","tabId":"w1:t2","paneId":"$NEW_PANE"}""").setResponseCode(201)
                        }
                        "/v1/panes/$OLD_PANE/conversation" -> json(conversation(new = misrouteOld.get()))
                        "/v1/panes/$NEW_PANE/conversation" -> json(conversation(new = true))
                        "/v1/panes/$OLD_PANE/messages" -> { messages += JSONObject(request.body.readUtf8()); json("""{"ok":true}""") }
                        "/v1/panes/$OLD_PANE/attachments/acknowledge" -> { acknowledgements.incrementAndGet(); json("""{"ok":true}""") }
                        else -> { if (request.method != "GET") unexpectedWrites += path; MockResponse().setResponseCode(404) }
                    }
                }
            }
            server.start()
        }

        private fun conversation(new: Boolean): String {
            val id = if (new) NEW_ID else OLD_ID
            val binding = if (new) NEW_BINDING else if (rotateOldBinding.get()) ROTATED_BINDING else OLD_BINDING
            return JSONObject().put("conversationId", id).put("binding", binding).put("revision", "revision-$id-$binding")
                .put("historyEpoch", "history-$id").put("unchanged", false).put("canSend", true).put("status", "idle")
                .put("attachmentDeliveryUncertain", if (new) newUncertain.get() else oldUncertain.get())
                .put("messages", JSONArray().put(JSONObject().put("id", "answer-$id").put("role", "assistant").put("text", if (new) NEW_TEXT else OLD_TEXT))).toString()
        }

        private fun snapshot(): String {
            fun pane(id: String, tab: String, title: String) = JSONObject().put("id", id).put("workspaceId", "w1").put("tabId", tab)
                .put("title", title).put("cwd", "/tmp/synthetic").put("agent", "codex").put("status", "idle")
                .put("conversation", JSONObject().put("provider", "codex").put("available", true))
            val tabs = JSONArray().put(JSONObject().put("id", "w1:t1").put("workspaceId", "w1").put("label", "Original"))
            val panes = JSONArray().put(pane(OLD_PANE, "w1:t1", "Original Codex"))
            if (creations.get() > 0) {
                tabs.put(JSONObject().put("id", "w1:t2").put("workspaceId", "w1").put("label", "New chat"))
                panes.put(pane(NEW_PANE, "w1:t2", "New Codex"))
            }
            return JSONObject().put("workspaces", JSONArray().put(JSONObject().put("id", "w1").put("label", "Isolated chat fixture").put("cwd", "/tmp/synthetic")))
                .put("tabs", tabs).put("panes", panes).toString()
        }

        fun connect() {
            compose.onNodeWithContentDescription("Add server").performClick()
            compose.onNode(hasSetTextAction() and hasText("Server name")).performTextInput("Synthetic chat isolation")
            compose.onNode(hasSetTextAction() and hasText("Bridge address")).performTextInput(server.url("/").toString())
            compose.onNode(hasSetTextAction() and hasText("Access token")).performTextInput(TOKEN)
            compose.onNodeWithText("Save & connect").performClick()
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[HerdrViewModel::class.java] }
            compose.waitUntil(10_000) { vm.state.value.connection == ConnectionState.CONNECTED && compose.onAllNodesWithContentDescription("Open conversation").fetchSemanticsNodes().isNotEmpty() }
        }

        fun openOld() {
            connect()
            compose.onNodeWithContentDescription("Open conversation").performClick()
            compose.waitUntil(10_000) { vm.state.value.chat.conversationId == OLD_ID && vm.state.value.chat.canSend }
            awaitText(OLD_TEXT)
        }

        override fun close() {
            compose.runOnIdle { ViewModelProvider(compose.activity)[HerdrViewModel::class.java].disconnect() }
            profiles.profiles().filterNot { it.id in originalProfiles }.forEach { profiles.remove(it.id) }
            compose.waitForIdle()
            server.shutdown()
        }
    }

    private companion object {
        const val OLD_PANE = "w1:p1"
        const val NEW_PANE = "w1:p2"
        const val OLD_ID = "00000000-0000-4000-8000-000000000001"
        const val NEW_ID = "00000000-0000-4000-8000-000000000002"
        const val OLD_BINDING = "synthetic-original-binding"
        const val NEW_BINDING = "synthetic-new-binding"
        const val ROTATED_BINDING = "synthetic-original-rotated-binding"
        const val OLD_TEXT = "Original synthetic conversation remains visible."
        const val NEW_TEXT = "New synthetic conversation has separate messages."
        const val TOKEN = "native-conversation-isolation-test-token"
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
}
