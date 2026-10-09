package dev.herdr.android.ui

import androidx.compose.ui.test.assertIsEnabled
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
import dev.herdr.android.data.ProfileStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/** A lost creation acknowledgement cannot turn background polling into permission to retry. */
@RunWith(AndroidJUnit4::class)
class NewTabUncertaintyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun acknowledgementCannotOpenAnExistingPaneAsANewTab() {
        Fixture(reusedAcknowledgement = true).use { fixture ->
            fixture.connect()
            compose.onNodeWithTag("new-tab").performClick()
            compose.onNodeWithTag("new-tab-name").performTextReplacement(CREATED_LABEL)
            compose.onNodeWithTag("create-tab").performClick()
            compose.waitUntil(10_000) {
                fixture.creationWrites.get() == 1 && !fixture.vm.state.value.creatingTab && fixture.vm.state.value.creationUncertain
            }
            compose.runOnIdle {
                assertNull("An existing pane cannot become the new-tab selection", fixture.vm.state.value.paneId)
                assertEquals(0L, fixture.vm.state.value.creationRevision)
            }
            compose.onNodeWithTag("create-tab").assertIsNotEnabled().performTouchInput { click() }
            assertEquals(1, fixture.creationWrites.get())
        }
    }

    @Test fun automaticRefreshKeepsUncertainCreationGuardUntilExplicitInspection() {
        Fixture().use { fixture ->
            fixture.connect()
            compose.onNodeWithTag("new-tab").performClick()
            compose.onNodeWithTag("new-tab-name").performTextReplacement(CREATED_LABEL)
            compose.onNodeWithTag("create-tab").performClick()
            compose.waitUntil(10_000) {
                fixture.creationWrites.get() == 1 && !fixture.vm.state.value.creatingTab && fixture.vm.state.value.creationUncertain
            }
            compose.onNodeWithTag("create-tab").assertIsNotEnabled()

            // Let the normal three-second poll finish successfully. Its snapshot contains the
            // server-side tab, but it must not acknowledge the uncertain POST on the user's behalf.
            val readsAfterFailure = fixture.snapshotReads.get()
            compose.waitUntil(10_000) {
                val state = fixture.vm.state.value
                fixture.snapshotReads.get() > readsAfterFailure && !state.refreshing && state.error == null &&
                    state.snapshot?.tabs?.any { it.label == CREATED_LABEL } == true
            }
            compose.runOnIdle { assertTrue(fixture.vm.state.value.creationUncertain) }
            compose.onNodeWithTag("create-tab").assertIsNotEnabled().performTouchInput { click() }
            compose.runOnIdle { fixture.vm.createTab("w1", CREATED_LABEL, "/tmp/synthetic-project", false) }
            compose.waitForIdle()
            assertEquals("Neither disabled UI nor the ViewModel may retry the creation", 1, fixture.creationWrites.get())

            val readsBeforeInspection = fixture.snapshotReads.get()
            compose.onNodeWithTag("inspect-created-tabs").performClick()
            compose.waitUntil(10_000) {
                fixture.snapshotReads.get() > readsBeforeInspection && !fixture.vm.state.value.creationUncertain &&
                    fixture.vm.state.value.workspaceId == "w1"
            }
            compose.onNodeWithTag("create-tab").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(fixture.vm.state.value.snapshot?.tabs?.any { it.label == CREATED_LABEL } == true)
                assertEquals(1, fixture.creationWrites.get())
            }
            compose.onNodeWithTag("new-tab").performClick()
            compose.onNodeWithTag("create-tab").assertIsEnabled()
            compose.onNodeWithTag("inspect-created-tabs").assertDoesNotExist()
        }
    }

    private inner class Fixture(private val reusedAcknowledgement: Boolean = false) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val profiles = ProfileStore(context)
        private val originalIds = profiles.profiles().map { it.id }.toSet()
        private val server = MockWebServer()
        val creationWrites = AtomicInteger()
        val snapshotReads = AtomicInteger()
        lateinit var vm: HerdrViewModel
        private val baseUrl: String

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer $TOKEN") return MockResponse().setResponseCode(401)
                    return when (request.requestUrl!!.encodedPath) {
                        "/v1/health" -> json("""{"service":"herdr-android-bridge","protocol":1,"capabilities":["workspace-tabs"]}""")
                        "/v1/snapshot" -> {
                            assertEquals("GET", request.method)
                            snapshotReads.incrementAndGet()
                            json(snapshot())
                        }
                        "/v1/workspaces/w1/tabs" -> {
                            assertEquals("POST", request.method)
                            assertEquals(CREATED_LABEL, JSONObject(request.body.readUtf8()).getString("label"))
                            creationWrites.incrementAndGet()
                            if (reusedAcknowledgement) return json("""{"workspaceId":"w1","tabId":"w1:t1","paneId":"w1:p1"}""")
                                .setResponseCode(201)
                            // Creation really happened; the phone did not receive its acknowledgement.
                            json("""{"detail":"The creation outcome could not be confirmed."}""").setResponseCode(503)
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
            baseUrl = server.url("/").toString()
        }

        private fun snapshot(): String {
            val tabs = JSONArray("""[{"id":"w1:t1","workspaceId":"w1","label":"main"}]""")
            val panes = JSONArray("""[{"id":"w1:p1","workspaceId":"w1","tabId":"w1:t1","title":"Existing terminal","cwd":"/tmp/synthetic-project","status":"idle"}]""")
            if (creationWrites.get() > 0) {
                tabs.put(JSONObject().put("id", "w1:t2").put("workspaceId", "w1").put("label", CREATED_LABEL))
                panes.put(JSONObject().put("id", "w1:p2").put("workspaceId", "w1").put("tabId", "w1:t2")
                    .put("title", CREATED_LABEL).put("cwd", "/tmp/synthetic-project").put("status", "idle"))
            }
            return JSONObject().put("herdrVersion", "0.9.3")
                .put("workspaces", JSONArray("""[{"id":"w1","label":"Isolated creation project","cwd":"/tmp/synthetic-project"}]"""))
                .put("tabs", tabs).put("panes", panes).toString()
        }

        fun connect() {
            compose.onNodeWithContentDescription("Add server").performClick()
            compose.onNode(hasSetTextAction() and hasText("Server name")).performTextInput("Uncertain creation fixture")
            compose.onNode(hasSetTextAction() and hasText("Bridge address")).performTextInput(baseUrl)
            compose.onNode(hasSetTextAction() and hasText("Access token")).performTextInput(TOKEN)
            compose.onNodeWithText("Save & connect").performClick()
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[HerdrViewModel::class.java] }
            compose.waitUntil(10_000) { vm.state.value.connection == ConnectionState.CONNECTED && vm.state.value.workspaceId == "w1" }
            assertEquals(0, creationWrites.get())
        }

        override fun close() {
            compose.runOnIdle { ViewModelProvider(compose.activity)[HerdrViewModel::class.java].disconnect() }
            profiles.profiles().filter {
                it.id !in originalIds && it.baseUrl.removeSuffix("/") == baseUrl.removeSuffix("/")
            }.forEach { profiles.remove(it.id) }
            server.shutdown()
        }
    }

    private companion object {
        const val TOKEN = "native-uncertain-creation-isolated-test-token"
        const val CREATED_LABEL = "Created despite lost acknowledgement"
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
}
