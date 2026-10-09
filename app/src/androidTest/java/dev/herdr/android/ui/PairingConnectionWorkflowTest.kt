package dev.herdr.android.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Runs the actual invitation confirmation, Keystore save and dashboard transition. */
@RunWith(AndroidJUnit4::class)
class PairingConnectionWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun confirmingPairingWithVersion02HealthPersistsTheDeviceAndOpensTheDashboard() {
        Fixture(codexChat = false).use { fixture -> fixture.confirmAndConnect() }
    }

    @Test
    fun confirmingPairingWithVersion03HealthDisplaysMixedAgentStatusesAndTabs() {
        Fixture(codexChat = true).use { fixture -> fixture.confirmAndConnect() }
    }

    @Test
    fun duplicatePaneIdsAfterPairingShowAConnectionErrorWithoutCrashingTheDashboard() {
        Fixture(codexChat = true, duplicatePaneIds = true).use { fixture -> fixture.confirmAndExpectSnapshotError() }
    }

    private inner class Fixture(codexChat: Boolean, duplicatePaneIds: Boolean = false) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val profiles = ProfileStore(context)
        private val originalProfileIds = profiles.profiles().map { it.id }.toSet()
        private val code = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 11 })
        private val token = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { 12 })
        private val server = MockWebServer()
        private val pairRequests = AtomicInteger()
        private val authenticatedRequests = CopyOnWriteArrayList<String>()
        private val commandRequests = AtomicInteger()
        private val baseUrl: String
        private lateinit var vm: HerdrViewModel

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when (path) {
                        "/v1/pair" -> {
                            pairRequests.incrementAndGet()
                            val body = JSONObject(request.body.readUtf8())
                            if (body.optString("code") != code || body.optString("deviceName").isBlank() || request.getHeader("Authorization") != null) {
                                MockResponse().setResponseCode(400)
                            } else json("""{"service":"herdr-android-bridge","protocol":1,"token":"$token","deviceId":"12345678-1234-4234-9234-123456789abc","serverName":"Pairing workflow fixture"}""")
                        }
                        "/v1/health" -> {
                            if (request.getHeader("Authorization") != "Bearer $token") MockResponse().setResponseCode(401)
                            else {
                                authenticatedRequests.add(path)
                                json(if (codexChat) """{"service":"herdr-android-bridge","protocol":1,"herdrVersion":"0.9.3","pairing":true,"capabilities":["codex-chat"]}"""
                                    else """{"service":"herdr-android-bridge","protocol":1,"herdrVersion":"0.9.3","pairing":true}""")
                            }
                        }
                        "/v1/snapshot" -> {
                            if (request.getHeader("Authorization") != "Bearer $token") MockResponse().setResponseCode(401)
                            else {
                                authenticatedRequests.add(path)
                                val snapshot = """{"herdrVersion":"0.9.3","workspaces":[{"id":"w1","label":"Project agents","cwd":"/tmp/synthetic-project"},{"id":"w2","label":"Other workspace","cwd":"/tmp/synthetic-other"}],"tabs":[{"id":"w1:t1","workspaceId":"w1","label":"Development"},{"id":"w1:t2","workspaceId":"w1","label":"Checks"},{"id":"w2:t1","workspaceId":"w2","label":"Planning"}],"panes":[{"id":"w1:p1","workspaceId":"w1","tabId":"w1:t1","title":"Claude needs approval","cwd":"/tmp/synthetic-project","agent":"claude","status":"blocked"},{"id":"w1:p2","workspaceId":"w1","tabId":"w1:t1","title":"Codex working","cwd":"/tmp/synthetic-project","agent":"codex","status":"working","conversation":{"provider":"codex","available":true}},{"id":"w1:p3","workspaceId":"w1","tabId":"w1:t1","title":"Shell ready","cwd":"/tmp/synthetic-project","status":"idle"},{"id":"w1:p4","workspaceId":"w1","tabId":"w1:t2","title":"Checks shell","cwd":"/tmp/synthetic-project","status":"done"},{"id":"w2:p1","workspaceId":"w2","tabId":"w2:t1","title":"Other shell","cwd":"/tmp/synthetic-other","status":"unknown"}]}"""
                                json(if (duplicatePaneIds) snapshot.replace("\"w1:p3\"", "\"w1:p1\"") else snapshot)
                            }
                        }
                        else -> {
                            if (request.method == "POST") commandRequests.incrementAndGet()
                            MockResponse().setResponseCode(404)
                        }
                    }
                }
            }
            server.start()
            // Constructing MockWebServer URLs can resolve an address; do it off the UI thread.
            baseUrl = server.url("/").toString()
        }

        fun confirmAndConnect() {
            confirmInvitation()
            compose.waitUntil(15_000) { vm.state.value.connection == ConnectionState.CONNECTED }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Workspaces").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Workspaces").assertIsDisplayed()
            compose.onNodeWithText("Needs your input").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Claude needs approval").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Shell ready").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("confirm-pairing").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(1, pairRequests.get())
                assertEquals(0, commandRequests.get())
                assertTrue(authenticatedRequests.containsAll(listOf("/v1/health", "/v1/snapshot")))
                val profileId = vm.state.value.activeProfileId!!
                assertFalse(profileId in originalProfileIds)
                assertEquals("Pairing workflow fixture", profiles.profiles().single { it.id == profileId }.name)
                assertEquals("w1", vm.state.value.workspaceId)
                assertEquals("w1:t1", vm.state.value.tabId)
                assertEquals(5, vm.state.value.snapshot!!.panes.size)
                val stored = context.getSharedPreferences("server_profiles", Context.MODE_PRIVATE).getString("profiles", "")!!
                assertFalse("The invitation must not be persisted", stored.contains(code))
                assertFalse("The device token must be encrypted", stored.contains(token))
            }
        }

        fun confirmAndExpectSnapshotError() {
            confirmInvitation()
            compose.waitUntil(15_000) { vm.state.value.connection == ConnectionState.ERROR }
            compose.onNodeWithText("The server returned an invalid workspace snapshot. Reconnect or update the host companion.")
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("confirm-pairing").assertDoesNotExist()
            compose.runOnIdle {
                assertEquals(1, pairRequests.get())
                assertEquals(0, commandRequests.get())
                assertEquals(null, vm.state.value.snapshot)
                assertTrue(profiles.profiles().any { it.id == vm.state.value.activeProfileId })
            }
        }

        private fun confirmInvitation() {
            val invitation = """{"type":"herdr-pocket-pair","version":1,"url":"$baseUrl","name":"Pairing workflow fixture","code":"$code","expiresAt":9223372036854775807}"""
            compose.onNodeWithText("Paste pairing code").performScrollTo().performClick()
            compose.onNodeWithTag("pairing-payload").performTextInput(invitation)
            compose.onNodeWithTag("review-pairing").performClick()
            compose.onNodeWithText("Connect to Pairing workflow fixture").assertIsDisplayed()
            compose.runOnIdle { assertEquals(0, pairRequests.get()) }
            compose.onNodeWithTag("confirm-pairing").performScrollTo().performClick()
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[HerdrViewModel::class.java] }
        }

        override fun close() {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle { ViewModelProvider(compose.activity)[HerdrViewModel::class.java].disconnect() }
            profiles.profiles().filterNot { it.id in originalProfileIds }.forEach { profiles.remove(it.id) }
            server.shutdown()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
