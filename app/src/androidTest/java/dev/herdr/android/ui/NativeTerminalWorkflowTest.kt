package dev.herdr.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.HerdrViewModel
import dev.herdr.android.MainActivity
import dev.herdr.android.data.ConnectionState
import dev.herdr.android.data.PaneMode
import dev.herdr.android.data.ProfileStore
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** The complete native screen talks only to disposable synthetic loopback panes. */
@RunWith(AndroidJUnit4::class)
class NativeTerminalWorkflowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun shellScreenCommitsNativeCompositionBeforeTheVisibleEnterKeyWithoutAnsweringRemoteQueries() {
        Fixture().use { fixture ->
            fixture.open()
            compose.onNodeWithText("Shell project").performClick()
            fixture.awaitTerminal("w1:p1")
            compose.onNodeWithTag("chat-screen").assertDoesNotExist()
            compose.onNodeWithTag("terminal-surface").assertIsDisplayed()
            compose.runOnIdle { assertTrue("Output queries must not become input", fixture.inputs.isEmpty()) }

            compose.onNodeWithTag("terminal-keyboard").performClick()
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250L, 5_000L)
            compose.runOnIdle {
                val input = terminalInputView(compose.activity.window.decorView)
                val connection = input.onCreateInputConnection(EditorInfo())!!
                assertTrue(connection.setComposingText("echo hello", 1))
                assertTrue("A composing word stays local until the user submits it", fixture.inputs.isEmpty())
            }
            compose.onNodeWithTag("terminal-key-row").performScrollToNode(hasTestTag("key-enter"))
            compose.onNodeWithTag("key-enter").performClick()
            compose.waitUntil(10_000) { fixture.inputs.size == 2 && !fixture.vm.state.value.sending }
            assertEquals(listOf("/v1/panes/w1:p1/terminal-input", "/v1/panes/w1:p1/input"), fixture.inputs.map { it.path })
            assertEquals("echo hello", fixture.inputs[0].text)
            assertEquals(emptyList<String>(), fixture.inputs[0].keys)
            assertEquals(null, fixture.inputs[1].text)
            assertEquals(listOf("enter"), fixture.inputs[1].keys)
            assertEquals(0, fixture.conversationReads.get())

            // The fixture contains no real terminal contents, credentials or host identity.
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250L, 5_000L)
            if (fixture.vm.state.value.paneId == "w1:p1") fixture.saveScreenshot("terminal-0.4.png")
        }
    }

    @Test
    fun searchingAnotherWorkspaceOpensItsNonCodexTerminalAndVisibleCopyControlsCopyOnlyItsOutput() {
        Fixture().use { fixture ->
            fixture.open()
            compose.onNodeWithContentDescription("Find a session").performClick()
            compose.onNodeWithTag("session-search").performTextInput("planning")
            compose.onNodeWithText("Claude planning").performClick()
            fixture.awaitTerminal("w2:p1")
            compose.runOnIdle {
                assertEquals("w2", fixture.vm.state.value.workspaceId)
                assertEquals(PaneMode.TERMINAL, fixture.vm.state.value.paneMode)
                assertTrue(fixture.inputs.isEmpty())
            }
            compose.onNodeWithTag("chat-screen").assertDoesNotExist()
            compose.onNodeWithTag("terminal-tools").performClick()
            compose.onNodeWithTag("terminal-select").performScrollTo().performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("terminal-copy-selection").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("terminal-copy-selection").assertIsDisplayed().performClick()
            compose.runOnIdle {
                val copied = fixture.clipboard.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                assertTrue("Copy must use the selected agent pane's output", copied.contains("CLAUDE SYNTHETIC OUTPUT"))
                assertTrue("Selection and remote queries must never send commands", fixture.inputs.isEmpty())
                assertEquals(0, fixture.conversationReads.get())
            }
        }
    }

    private data class Input(val path: String, val text: String?, val keys: List<String>)

    private inner class Fixture : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val profiles = ProfileStore(context)
        private val profileId = UUID.randomUUID().toString()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        private val originalClip = clipboard.primaryClip
        private val server = MockWebServer()
        val inputs = CopyOnWriteArrayList<Input>()
        val conversationReads = AtomicInteger()
        lateinit var vm: HerdrViewModel

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/v1/health" -> json("""{"service":"herdr-android-bridge","protocol":1,"herdrVersion":"0.9.3","capabilities":["terminal-input","terminal-history"]}""")
                        path == "/v1/snapshot" -> json("""{"herdrVersion":"0.9.3","workspaces":[{"id":"w1","label":"Shell workspace","cwd":"/tmp/synthetic-shell"},{"id":"w2","label":"Other project","cwd":"/tmp/synthetic-agent"}],"tabs":[{"id":"w1:t1","workspaceId":"w1","label":"main"},{"id":"w2:t1","workspaceId":"w2","label":"planning"}],"panes":[{"id":"w1:p1","workspaceId":"w1","tabId":"w1:t1","title":"Shell project","cwd":"/tmp/synthetic-shell","status":"idle"},{"id":"w2:p1","workspaceId":"w2","tabId":"w2:t1","title":"Claude planning","cwd":"/tmp/synthetic-agent","agent":"claude","status":"idle"}]}""")
                        path.endsWith("/stream") -> {
                            val ansi = syntheticFrame(if (path.contains("w2:p1")) "CLAUDE SYNTHETIC OUTPUT" else "SHELL SYNTHETIC OUTPUT")
                            val bytes = Base64.getEncoder().encodeToString(ansi.toByteArray(Charsets.UTF_8))
                            MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                                override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                                    webSocket.send("""{"type":"terminal.frame","bytes":"$bytes","encoding":"ansi","full":true,"width":80,"height":30,"seq":1}""")
                                }
                            })
                        }
                        path.endsWith("/input") || path.endsWith("/terminal-input") -> {
                            val body = JSONObject(request.body.readUtf8())
                            val keys = body.optJSONArray("keys")
                            inputs.add(Input(path, if (body.has("text") && !body.isNull("text")) body.getString("text") else null,
                                if (keys == null) emptyList() else List(keys.length()) { keys.getString(it) }))
                            json("""{"ok":true}""")
                        }
                        path.endsWith("/conversation") -> { conversationReads.incrementAndGet(); MockResponse().setResponseCode(404) }
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
            server.start()
        }

        fun open() {
            // MockWebServer resolves its bound address when constructing this URL.
            // Keep that platform operation off Android's main thread.
            val baseUrl = server.url("/").toString()
            compose.runOnIdle {
                vm = ViewModelProvider(compose.activity)[HerdrViewModel::class.java]
                assertTrue(vm.saveProfile("Terminal workflow fixture", baseUrl, "native-terminal-isolated-public-test-token", profileId))
                vm.connect(profileId)
            }
            compose.waitUntil(10_000) { vm.state.value.connection == ConnectionState.CONNECTED }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("terminal-surface").fetchSemanticsNodes().isEmpty() }
        }

        fun awaitTerminal(paneId: String) {
            compose.waitUntil(10_000) { vm.state.value.paneId == paneId && vm.state.value.terminal.canInput }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("terminal-input-view").fetchSemanticsNodes().isNotEmpty() }
            compose.waitForIdle()
        }

        fun saveScreenshot(name: String) {
            val destination = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
            InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                try { File(destination, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                finally { bitmap.recycle() }
            }
        }

        override fun close() {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            if (::vm.isInitialized) compose.runOnIdle { vm.disconnect() }
            profiles.remove(profileId)
            compose.runOnIdle {
                if (originalClip != null) clipboard.setPrimaryClip(originalClip)
                else if (android.os.Build.VERSION.SDK_INT >= 28) clipboard.clearPrimaryClip()
            }
            server.shutdown()
        }
    }

    private fun terminalInputView(view: View): TerminalInputView {
        if (view is TerminalInputView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                runCatching { terminalInputView(view.getChildAt(index)) }.getOrNull()?.let { return it }
            }
        }
        throw IllegalStateException("The selected terminal has no native input view.")
    }

    private fun syntheticFrame(label: String): String = "\u001B[2J\u001B[H" +
        "\u001B[38;2;129;230;185m$label\u001B[0m\r\n" +
        "Native terminal - local test server\r\n" +
        "\u001B[21;1H$ git status --short\r\n M src/server.kt\r\n M README.md" +
        "\u001B[26;1H$ echo hello\r\nhello\r\n" +
        "\u001B[38;2;129;230;185muser@dust\u001B[0m ~/project $ \u001B[6n\u001B[c"

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
}
