package dev.herdr.android.ui

import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Recovery reads exercise the real activity without selecting or writing to any real chat. */
@RunWith(AndroidJUnit4::class)
class NativeChatRecoveryDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun temporary404409And503RecoverAutomaticallyWithoutSelectingOrSending() {
        Fixture(initialFailures = listOf(404, 409, 503)).use { fixture ->
            fixture.openA(waitForPage = false)
            awaitRetry(fixture)
            compose.onNodeWithTag("chat-read-retrying").assertIsDisplayed()
            compose.onNodeWithText("Waiting for this tab’s conversation").assertIsDisplayed()
            compose.onNodeWithText("Conversation needs a refresh").assertDoesNotExist()
            compose.onNodeWithText("Conversation unavailable").assertDoesNotExist()
            compose.onNodeWithTag("send-chat").assertIsNotEnabled()
            fixture.awaitA()
            assertTrue(fixture.readsA.get() >= 4)
            compose.onNodeWithTag("chat-read-retrying").assertDoesNotExist()
            compose.onNodeWithTag("codex-conversation-selector").assertDoesNotExist()
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test fun temporaryReadFailureKeepsTheOriginalMessagesAndDraftUntilExactRecovery() {
        Fixture().use { fixture ->
            fixture.openA()
            compose.onNodeWithTag("chat-draft").performTextInput("Reviewed original draft")
            fixture.hideKeyboard()
            fixture.outageStatus.set(503)
            awaitRetry(fixture)
            awaitText(ORIGINAL_TEXT)
            compose.onNodeWithTag("chat-draft").assertTextEquals("Reviewed original draft")
            compose.onNodeWithTag("send-chat").assertIsNotEnabled()
            compose.runOnIdle { fixture.vm.sendChat() }
            assertTrue(fixture.writes.isEmpty())
            fixture.outageStatus.set(0)
            fixture.awaitA()
            assertEquals(A_ID, fixture.vm.state.value.chat.conversationId)
            compose.onNodeWithTag("chat-draft").assertTextEquals("Reviewed original draft")
            compose.onNodeWithTag("chat-read-retrying").assertDoesNotExist()
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test fun hidingTheFailedTabStopsItsRetriesWhileAnotherTabsConversationStillWorks() {
        Fixture().use { fixture ->
            fixture.openA()
            fixture.outageStatus.set(503)
            awaitRetry(fixture)
            compose.onNodeWithContentDescription("Back to workspaces").performClick()
            compose.runOnIdle { fixture.vm.selectPane(B_PANE) }
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.conversationId == B_ID && fixture.vm.state.value.chat.canSend }
            awaitText(HEALTHY_TEXT)
            Thread.sleep(250)
            val hiddenReads = fixture.readsA.get()
            Thread.sleep(1_500)
            assertEquals("Hidden conversations must not continue retrying", hiddenReads, fixture.readsA.get())
            fixture.outageStatus.set(0)
            compose.runOnIdle { fixture.vm.selectPane(A_PANE) }
            fixture.awaitA()
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test fun recoveringReadCannotReplaceTheOriginalChatWithAnotherUUID() {
        Fixture().use { fixture ->
            fixture.openA()
            compose.onNodeWithTag("chat-draft").performTextInput("Original conversation draft")
            fixture.hideKeyboard()
            fixture.outageStatus.set(503)
            awaitRetry(fixture)
            fixture.wrongConversation.set(true)
            fixture.outageStatus.set(0)
            compose.waitUntil(12_000) { fixture.vm.state.value.chat.conversationChanged }
            assertFalse(fixture.vm.state.value.chat.readRetrying)
            assertEquals(A_ID, fixture.vm.state.value.chat.conversationId)
            awaitText(ORIGINAL_TEXT)
            compose.onNodeWithText(WRONG_TEXT).assertDoesNotExist()
            compose.onNodeWithTag("chat-conversation-changed").assertIsDisplayed()
            compose.onNodeWithTag("chat-draft").assertTextEquals("Original conversation draft")
            compose.onNodeWithTag("send-chat").assertIsNotEnabled()
            compose.runOnIdle { fixture.vm.sendChat() }
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test fun authentication401StopsAutomaticReadsWithoutSending() = assertAuthenticationStops(401)
    @Test fun authentication403StopsAutomaticReadsWithoutSending() = assertAuthenticationStops(403)

    @Test fun verifiedTabAttributionClosesOnlyItsAutomaticChooserWithoutASelectionPost() {
        Fixture(initiallyAttributed = false).use { fixture ->
            fixture.openA(waitForPage = false)
            compose.waitUntil(10_000) { fixture.vm.state.value.codexSelection.showing && !fixture.vm.state.value.codexSelection.loading }
            assertTrue(fixture.vm.state.value.codexSelection.automatic)
            compose.onNodeWithTag("codex-conversation-selector").assertIsDisplayed()
            assertTrue(fixture.writes.isEmpty())
            fixture.attributed.set(true)
            // The ordinary snapshot refresh detects attribution; no Refresh/selection action is pressed.
            fixture.awaitA()
            compose.waitUntil(10_000) { !fixture.vm.state.value.codexSelection.showing }
            compose.onNodeWithTag("codex-conversation-selector").assertDoesNotExist()
            awaitText(ORIGINAL_TEXT)
            assertTrue(fixture.writes.isEmpty())
        }
    }

    @Test fun aDeliberatelyOpenedChooserStaysOpenWhenItsOriginalConversationReadRecovers() {
        Fixture().use { fixture ->
            fixture.openA()
            compose.onNodeWithTag("choose-codex-conversation").performClick()
            compose.waitUntil(10_000) { fixture.vm.state.value.codexSelection.showing && !fixture.vm.state.value.codexSelection.loading }
            assertFalse(fixture.vm.state.value.codexSelection.automatic)
            fixture.outageStatus.set(503)
            awaitRetry(fixture)
            fixture.outageStatus.set(0)
            fixture.awaitA(checkVisibleMessage = false)
            assertTrue(fixture.vm.state.value.codexSelection.showing)
            compose.onNodeWithTag("codex-conversation-selector").assertIsDisplayed()
            compose.onNodeWithText("Back to chat").performClick()
            awaitText(ORIGINAL_TEXT)
            assertTrue(fixture.writes.isEmpty())
        }
    }

    private fun assertAuthenticationStops(status: Int) {
        Fixture().use { fixture ->
            fixture.outageStatus.set(status)
            fixture.openA(waitForPage = false)
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.error != null }
            assertFalse(fixture.vm.state.value.chat.readRetrying)
            compose.onNodeWithText("Could not open the conversation").assertIsDisplayed()
            val attempts = fixture.readsA.get()
            Thread.sleep(1_500)
            assertEquals(attempts, fixture.readsA.get())
            compose.runOnIdle { fixture.vm.sendChat() }
            assertTrue(fixture.writes.isEmpty())
        }
    }

    private fun awaitRetry(fixture: Fixture) = compose.waitUntil(10_000) { fixture.vm.state.value.chat.readRetrying }
    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text).assertIsDisplayed()
    }

    private inner class Fixture(initialFailures: List<Int> = emptyList(), initiallyAttributed: Boolean = true) : AutoCloseable {
        private val profiles = ProfileStore(InstrumentationRegistry.getInstrumentation().targetContext)
        private val profileId = UUID.randomUUID().toString()
        private val errors = ConcurrentLinkedQueue(initialFailures)
        private val server = MockWebServer()
        val attributed = AtomicBoolean(initiallyAttributed)
        val outageStatus = AtomicInteger()
        val wrongConversation = AtomicBoolean()
        val readsA = AtomicInteger()
        val writes = CopyOnWriteArrayList<String>()
        lateinit var vm: HerdrViewModel

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl?.encodedPath.orEmpty()
                    if (request.method != "GET") {
                        writes += path
                        return json("""{"ok":true}""")
                    }
                    return when (path) {
                        "/v1/health" -> json("""{"service":"herdr-android-bridge","protocol":1,"capabilities":["codex-chat","codex-chat-selection"]}""")
                        "/v1/snapshot" -> json(snapshot())
                        "/v1/panes/$A_PANE/codex-conversations" -> json("""{"conversations":[{"id":"$A_ID","title":"Synthetic original conversation","createdAt":1700000000,"status":"idle"}],"selectionToken":"${"a".repeat(43)}"}""")
                        "/v1/panes/$A_PANE/conversation" -> {
                            readsA.incrementAndGet()
                            val status = errors.poll() ?: outageStatus.get()
                            if (status != 0) json("""{"detail":"Synthetic conversation read temporarily failed."}""").setResponseCode(status)
                            else json(page(if (wrongConversation.get()) WRONG_ID else A_ID, if (wrongConversation.get()) WRONG_TEXT else ORIGINAL_TEXT))
                        }
                        "/v1/panes/$B_PANE/conversation" -> json(page(B_ID, HEALTHY_TEXT))
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }

        fun openA(waitForPage: Boolean = true) {
            val url = server.url("/").toString()
            compose.runOnIdle {
                vm = ViewModelProvider(compose.activity)[HerdrViewModel::class.java]
                assertTrue(vm.saveProfile("Synthetic recovery fixture", url, "native-recovery-public-test-token", profileId))
                vm.connect(profileId)
            }
            compose.waitUntil(10_000) { vm.state.value.connection == ConnectionState.CONNECTED }
            compose.runOnIdle { vm.selectPane(A_PANE) }
            if (waitForPage) awaitA()
        }

        fun awaitA(checkVisibleMessage: Boolean = true) {
            compose.waitUntil(15_000) { vm.state.value.chat.conversationId == A_ID && vm.state.value.chat.canSend && !vm.state.value.chat.readRetrying }
            if (checkVisibleMessage) awaitText(ORIGINAL_TEXT)
        }

        fun hideKeyboard() {
            compose.runOnIdle {
                compose.activity.getSystemService(InputMethodManager::class.java)
                    .hideSoftInputFromWindow(compose.activity.window.decorView.windowToken, 0)
            }
            compose.waitForIdle()
        }

        private fun snapshot(): String {
            val tabs = JSONArray().put(JSONObject().put("id", "w1:t1").put("workspaceId", "w1").put("label", "Original tab"))
                .put(JSONObject().put("id", "w1:t2").put("workspaceId", "w1").put("label", "Healthy tab"))
            fun pane(id: String, tab: String, available: Boolean) = JSONObject().put("id", id).put("workspaceId", "w1").put("tabId", tab)
                .put("title", if (id == A_PANE) "Original Codex" else "Healthy Codex").put("cwd", "/tmp/synthetic-recovery")
                .put("agent", "codex").put("status", "idle")
                .put("conversation", JSONObject().put("provider", "codex").put("available", available).put("selectable", true))
            return JSONObject().put("workspaces", JSONArray().put(JSONObject().put("id", "w1").put("label", "Recovery fixture").put("cwd", "/tmp/synthetic-recovery")))
                .put("tabs", tabs).put("panes", JSONArray().put(pane(A_PANE, "w1:t1", attributed.get())).put(pane(B_PANE, "w1:t2", true))).toString()
        }

        override fun close() {
            if (::vm.isInitialized) compose.runOnIdle { vm.disconnect() }
            profiles.remove(profileId)
            server.shutdown()
            compose.waitForIdle()
        }
    }

    private companion object {
        const val A_PANE = "w1:p1"
        const val B_PANE = "w1:p2"
        const val A_ID = "00000000-0000-4000-8000-000000000001"
        const val B_ID = "00000000-0000-4000-8000-000000000002"
        const val WRONG_ID = "00000000-0000-4000-8000-000000000003"
        const val ORIGINAL_TEXT = "Original tab synthetic conversation."
        const val HEALTHY_TEXT = "Healthy tab synthetic conversation."
        const val WRONG_TEXT = "Different synthetic conversation must remain hidden."
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
        fun page(id: String, text: String): String = JSONObject().put("binding", "binding-$id").put("conversationId", id)
            .put("revision", "r1").put("historyEpoch", "e1").put("messages", JSONArray().put(JSONObject().put("id", "assistant-$id").put("role", "assistant").put("text", text)))
            .put("unchanged", false).put("canSend", true).put("status", "idle").toString()
    }
}
