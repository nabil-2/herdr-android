package dev.herdr.android.data

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.HerdrViewModel
import dev.herdr.android.MainActivity
import okhttp3.WebSocket
import okhttp3.WebSocketListener
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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** ViewModel writes use only these disposable, synthetic loopback panes. */
@RunWith(AndroidJUnit4::class)
class TerminalInputDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun delayedTypingAndSpecialKeysStayOrderedAndOnlyComposerAcknowledgementClearsTheDraft() {
        Fixture().use { fixture ->
            fixture.open()
            fixture.blockNext.set(true)
            val revision = fixture.vm.state.value.inputRevision
            compose.runOnIdle { assertTrue(fixture.vm.sendTerminalInput("雪".toByteArray())) }
            compose.waitUntil(10_000) { fixture.inputs.size == 1 }
            compose.runOnIdle {
                assertTrue(fixture.vm.state.value.sending)
                assertTrue(fixture.vm.state.value.terminal.canInput)
                assertTrue(fixture.vm.sendTerminalInput("😀".toByteArray()))
                assertTrue(fixture.vm.sendTerminalInput(byteArrayOf(3)))
                assertTrue(fixture.vm.sendKeys("left"))
                assertTrue(fixture.vm.sendText("echo fixture"))
                assertEquals(revision, fixture.vm.state.value.inputRevision)
            }
            fixture.release.countDown()
            compose.waitUntil(10_000) { fixture.inputs.size == 4 && !fixture.vm.state.value.sending }
            assertEquals(listOf("雪", "😀\u0003", null, "echo fixture"), fixture.inputs.map { it.text })
            assertEquals(listOf("/v1/panes/w1:p1/terminal-input", "/v1/panes/w1:p1/terminal-input",
                "/v1/panes/w1:p1/input", "/v1/panes/w1:p1/input"), fixture.inputs.map { it.path })
            assertEquals(listOf("left"), fixture.inputs[2].keys)
            assertEquals(listOf("enter"), fixture.inputs[3].keys)
            assertEquals(revision + 1, fixture.vm.state.value.inputRevision)
        }
    }

    @Test fun switchingPanesDiscardsQueuedKeysAndUncertainOldPaneRequiresReviewBeforeContinuing() {
        Fixture().use { fixture ->
            fixture.open()
            fixture.blockNext.set(true)
            val oldScope = fixture.vm.captureTerminalInputScope(fixture.vm.state.value.activeProfileId,
                "w1:p1", fixture.vm.state.value.terminal.revision)
            assertTrue(oldScope != null)
            compose.runOnIdle { assertTrue(fixture.vm.sendTerminalInput("old in flight".toByteArray())) }
            compose.waitUntil(10_000) { fixture.inputs.size == 1 }
            compose.runOnIdle {
                assertTrue(fixture.vm.sendTerminalInput("must be discarded".toByteArray()))
                fixture.vm.selectPane("w1:p2")
            }
            compose.waitUntil(10_000) { fixture.vm.state.value.paneId == "w1:p2" && fixture.vm.state.value.terminal.canInput }
            fixture.release.countDown()
            compose.runOnIdle {
                assertFalse(fixture.vm.sendTerminalInput(oldScope, "stale keyboard".toByteArray()))
                assertFalse(fixture.vm.sendKeys(oldScope, listOf("enter")))
                assertFalse(fixture.vm.sendText(oldScope, "stale composer"))
                assertTrue(fixture.vm.sendTerminalInput("new pane".toByteArray()))
            }
            compose.waitUntil(10_000) { fixture.inputs.size == 2 && !fixture.vm.state.value.sending }
            assertEquals(listOf("old in flight", "new pane"), fixture.inputs.map { it.text })
            assertEquals("/v1/panes/w1:p2/terminal-input", fixture.inputs.last().path)
            compose.runOnIdle { fixture.vm.selectPane("w1:p1") }
            compose.waitUntil(10_000) { fixture.vm.state.value.terminal.connected && fixture.vm.state.value.paneId == "w1:p1" }
            compose.runOnIdle {
                assertTrue(fixture.vm.state.value.terminal.inputUncertain)
                assertFalse(fixture.vm.sendTerminalInput("refused".toByteArray()))
                fixture.vm.acknowledgeTerminalInput()
                assertTrue(fixture.vm.sendTerminalInput("reviewed new input".toByteArray()))
            }
            compose.waitUntil(10_000) { fixture.inputs.size == 3 && !fixture.vm.state.value.sending }
            assertEquals("reviewed new input", fixture.inputs.last().text)
        }
    }

    @Test fun backgroundingCancelsAndDiscardsInputWithoutReplayingItOnReconnect() {
        Fixture().use { fixture ->
            fixture.open()
            fixture.blockNext.set(true)
            compose.runOnIdle { assertTrue(fixture.vm.sendTerminalInput("maybe sent".toByteArray())) }
            compose.waitUntil(10_000) { fixture.inputs.size == 1 }
            compose.runOnIdle { assertTrue(fixture.vm.sendTerminalInput("must not replay".toByteArray())) }
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            fixture.release.countDown()
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertFalse(fixture.vm.state.value.terminal.canInput)
                assertFalse(fixture.vm.sendTerminalInput("background input".toByteArray()))
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { fixture.vm.state.value.terminal.connected }
            assertEquals(1, fixture.inputs.size)
            compose.runOnIdle {
                assertTrue(fixture.vm.state.value.terminal.inputUncertain)
                fixture.vm.acknowledgeTerminalInput()
                assertTrue(fixture.vm.sendTerminalInput("resumed intentional input".toByteArray()))
            }
            compose.waitUntil(10_000) { fixture.inputs.size == 2 && !fixture.vm.state.value.sending }
            assertEquals(listOf("maybe sent", "resumed intentional input"), fixture.inputs.map { it.text })
        }
    }

    @Test fun olderHostCompanionKeepsComposerAndLogicalKeysWhileRefusingRawTyping() {
        Fixture(rawSupported = false).use { fixture ->
            fixture.open()
            compose.runOnIdle {
                assertFalse(fixture.vm.sendTerminalInput("raw".toByteArray()))
                assertTrue(fixture.vm.sendKeys("enter"))
            }
            compose.waitUntil(10_000) { fixture.inputs.size == 1 && !fixture.vm.state.value.sending }
            assertEquals("/v1/panes/w1:p1/input", fixture.inputs.single().path)
            assertEquals(listOf("enter"), fixture.inputs.single().keys)
        }
    }

    @Test fun historyReadIsCancelledIfTheSelectedPaneChangesBeforeTheResponseArrives() {
        Fixture().use { fixture ->
            fixture.open()
            fixture.blockNextHistory.set(true)
            val result = AtomicReference<TerminalHistory?>(null)
            lateinit var read: Job
            compose.runOnIdle {
                read = compose.activity.lifecycleScope.launch { result.set(fixture.vm.readTerminalHistory()) }
            }
            compose.waitUntil(10_000) { fixture.historyReads.get() == 1 }
            compose.runOnIdle { fixture.vm.selectPane("w1:p2") }
            compose.waitUntil(10_000) { fixture.vm.state.value.terminal.canInput && fixture.vm.state.value.paneId == "w1:p2" }
            fixture.release.countDown()
            compose.waitUntil(10_000) { read.isCompleted }
            assertTrue(read.isCancelled)
            assertEquals(null, result.get())
            assertTrue(fixture.inputs.isEmpty())
        }
    }

    private data class Input(val path: String, val text: String?, val keys: List<String>)

    private inner class Fixture(rawSupported: Boolean = true) : AutoCloseable {
        val server = MockWebServer()
        val inputs = CopyOnWriteArrayList<Input>()
        val blockNext = AtomicBoolean(false)
        val blockNextHistory = AtomicBoolean(false)
        val historyReads = AtomicInteger()
        val release = CountDownLatch(1)
        private val profileId = UUID.randomUUID().toString()
        private val profileStore = ProfileStore(InstrumentationRegistry.getInstrumentation().targetContext)
        lateinit var vm: HerdrViewModel

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/v1/health" -> json("""{"service":"herdr-android-bridge","protocol":1,"herdrVersion":"0.9.3","capabilities":${if (rawSupported) "[\"terminal-input\",\"terminal-history\"]" else "[]"}}""")
                        path == "/v1/snapshot" -> json("""{"workspaces":[{"id":"w1","label":"Input fixture","cwd":"/tmp"}],"tabs":[{"id":"w1:t1","workspaceId":"w1","label":"main"}],"panes":[{"id":"w1:p1","workspaceId":"w1","tabId":"w1:t1","title":"First fixture","cwd":"/tmp"},{"id":"w1:p2","workspaceId":"w1","tabId":"w1:t1","title":"Second fixture","cwd":"/tmp"}]}""")
                        path.endsWith("/stream") -> MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                            override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                                webSocket.send("""{"type":"terminal.frame","bytes":"Zml4dHVyZQ==","encoding":"ansi","full":true,"width":80,"height":30,"seq":1}""")
                            }
                        })
                        path.endsWith("/input") || path.endsWith("/terminal-input") -> {
                            val body = JSONObject(request.body.readUtf8())
                            val keys = body.optJSONArray("keys")
                            inputs.add(Input(path, if (body.has("text") && !body.isNull("text")) body.getString("text") else null,
                                if (keys == null) emptyList() else List(keys.length()) { keys.getString(it) }))
                            if (blockNext.compareAndSet(true, false)) release.await(10, TimeUnit.SECONDS)
                            json("""{"ok":true}""")
                        }
                        path.endsWith("/scrollback") -> {
                            historyReads.incrementAndGet()
                            if (blockNextHistory.compareAndSet(true, false)) release.await(10, TimeUnit.SECONDS)
                            val paneId = path.substringAfter("/v1/panes/").substringBefore("/scrollback")
                            json("""{"paneId":"$paneId","text":"Disposable fixture history\n","truncated":false}""")
                        }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }

        fun open() {
            val baseUrl = server.url("/").toString()
            compose.runOnIdle {
                vm = ViewModelProvider(compose.activity)[HerdrViewModel::class.java]
                assertTrue(vm.saveProfile("Terminal input fixture", baseUrl, "isolated-terminal-input-public-test-token", profileId))
                vm.connect(profileId)
            }
            compose.waitUntil(10_000) { vm.state.value.connection == ConnectionState.CONNECTED }
            compose.runOnIdle { vm.selectPane("w1:p1") }
            compose.waitUntil(10_000) { vm.state.value.terminal.canInput }
        }

        override fun close() {
            release.countDown()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            if (::vm.isInitialized) compose.runOnIdle { vm.disconnect() }
            profileStore.remove(profileId)
            server.shutdown()
        }
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
