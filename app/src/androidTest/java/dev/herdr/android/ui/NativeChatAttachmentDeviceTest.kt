package dev.herdr.android.ui

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.HerdrViewModel
import dev.herdr.android.MainActivity
import dev.herdr.android.data.ChatAttachmentKind
import dev.herdr.android.data.ChatAttachmentStatus
import dev.herdr.android.data.ChatAttachmentTarget
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.io.File

/** Real editor/URI provider/staging/protocol/network flow, entirely synthetic. */
@RunWith(AndroidJUnit4::class)
class NativeChatAttachmentDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun clipboardImageAndFileAreReviewedThenSentOnceAndAcknowledgementClearsThem() {
        Fixture().use { fixture ->
            fixture.open()
            fixture.pasteImage()
            fixture.awaitReady(1)
            compose.runOnIdle { fixture.vm.stageChatAttachments(fixture.target(), listOf(uri("note.txt")), ChatAttachmentKind.FILE) }
            fixture.awaitReady(2)
            compose.onNodeWithText("screenshot.png").assertIsDisplayed()
            compose.onNodeWithText("note.txt").assertIsDisplayed()
            val screenshot = compose.onNodeWithTag("chat-screen").captureToImage().asAndroidBitmap()
            File(compose.activity.cacheDir, "chat-attachments-device.png").outputStream().use { assertTrue(screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            compose.onNodeWithTag("chat-draft").performTextInput("Review these exact files.\nKeep this text literal.")
            compose.onNodeWithTag("send-chat").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { fixture.messages.size == 1 && fixture.vm.state.value.chat.sending }
            compose.onNodeWithTag("send-chat").assertIsNotEnabled()
            compose.runOnIdle { fixture.vm.sendChat() }
            assertEquals(1, fixture.messages.size)
            val submitted = JSONObject(fixture.messages.single())
            assertEquals(BINDING, submitted.getString("binding"))
            assertEquals("Review these exact files.\nKeep this text literal.", submitted.getString("text"))
            assertEquals(fixture.uploads.map { it.id }, (0 until submitted.getJSONArray("attachmentIds").length()).map { submitted.getJSONArray("attachmentIds").getString(it) })
            assertTrue(fixture.uploads.first().bytes.take(8).toByteArray().contentEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)))
            assertEquals("Synthetic reviewed file.\nNo real user data.\n", fixture.uploads[1].bytes.toString(Charsets.UTF_8))
            fixture.releaseSend.countDown()
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.ackRevision == 1L && fixture.vm.state.value.chat.attachments.isEmpty() && fixture.vm.state.value.chat.draft.isEmpty() }
            assertFalse(fixture.vm.state.value.chat.deliveryUncertain)
            assertEquals(0, fixture.unexpectedInput.size)
        }
    }

    @Test fun keyboardImageCommitUsesActualEditorInputConnectionAndSupportsImageOnlySend() {
        Fixture().use { fixture ->
            fixture.open()
            compose.onNodeWithTag("chat-draft").performClick()
            compose.runOnIdle {
                val editor = findComposeEditor(compose.activity.window.decorView) ?: error("Missing native Compose editor")
                val info = EditorInfo()
                val connection = editor.onCreateInputConnection(info) ?: error("Editor did not open an input connection")
                assertTrue("Keyboard should advertise image MIME support", info.contentMimeTypes?.any { it == "*/*" || it == "image/*" } == true)
                val committed = connection.commitContent(InputContentInfo(uri("screenshot.png"), ClipDescription("Synthetic screenshot", arrayOf("image/png")), null), 0, null)
                assertTrue("Keyboard image commit should be accepted", committed)
            }
            fixture.awaitReady(1)
            assertEquals("", fixture.vm.state.value.chat.draft)
            compose.onNodeWithTag("send-chat").assertIsEnabled().performClick()
            compose.waitUntil(10_000) { fixture.messages.size == 1 }
            assertEquals("", JSONObject(fixture.messages.single()).getString("text"))
            fixture.releaseSend.countDown()
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.attachments.isEmpty() && fixture.vm.state.value.chat.ackRevision == 1L }
        }
    }

    @Test fun interruptedAttachmentDeliveryPreservesReviewBlocksRetryAndRequiresExplicitTerminalAcknowledgement() {
        Fixture(uncertainSend = true).use { fixture ->
            fixture.open()
            fixture.pasteImage()
            fixture.awaitReady(1)
            compose.onNodeWithTag("send-chat").assertIsEnabled().performClick()
            fixture.releaseSend.countDown()
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.attachmentDeliveryUncertain && !fixture.vm.state.value.chat.sending }
            compose.onNodeWithTag("send-chat").assertIsNotEnabled()
            assertEquals(ChatAttachmentStatus.DELIVERY_UNCERTAIN, fixture.vm.state.value.chat.attachments.single().status)
            compose.runOnIdle { fixture.vm.sendChat(); fixture.vm.stageChatAttachments(fixture.target(), listOf(uri("note.txt")), ChatAttachmentKind.FILE) }
            assertEquals(1, fixture.messages.size)
            assertEquals(1, fixture.uploads.size)
            compose.onNodeWithText("I checked and cleared Terminal").performClick()
            compose.waitUntil(10_000) { fixture.acknowledgements.size == 1 && !fixture.vm.state.value.chat.deliveryUncertain && fixture.vm.state.value.chat.attachments.isEmpty() }
            assertEquals(BINDING, fixture.acknowledgements.single())
            assertEquals(1, fixture.messages.size)
            compose.onNodeWithTag("send-chat").assertIsNotEnabled()
        }
    }

    @Test fun pickerBackgroundPreservesReadyReviewAndInvalidImageCannotUploadOrSend() {
        Fixture().use { fixture ->
            fixture.open()
            fixture.pasteImage()
            fixture.awaitReady(1)
            val ready = fixture.vm.state.value.chat.attachments.single()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.canSend }
            assertEquals(ready, fixture.vm.state.value.chat.attachments.single())
            compose.runOnIdle { fixture.vm.stageChatAttachments(fixture.target(), listOf(uri("invalid.png")), ChatAttachmentKind.IMAGE) }
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.attachments.any { it.status == ChatAttachmentStatus.FAILED } }
            compose.onNodeWithTag("send-chat").assertIsNotEnabled()
            assertEquals(1, fixture.uploads.size)
            assertTrue(fixture.messages.isEmpty())
            val failed = fixture.vm.state.value.chat.attachments.first { it.status == ChatAttachmentStatus.FAILED }
            compose.onNodeWithTag("remove-chat-attachment-${failed.localId}").performClick()
            compose.waitUntil(10_000) { fixture.vm.state.value.chat.attachments.size == 1 }
            compose.onNodeWithTag("send-chat").assertIsEnabled()
            val oldTarget = fixture.target()
            compose.runOnIdle { fixture.vm.selectPane(null); fixture.vm.stageChatAttachments(oldTarget, listOf(uri("note.txt")), ChatAttachmentKind.FILE) }
            assertTrue(fixture.vm.state.value.chat.attachments.isEmpty())
            assertTrue(fixture.messages.isEmpty())
        }
    }

    private data class Upload(val id: String, val bytes: ByteArray)
    private inner class Fixture(private val uncertainSend: Boolean = false) : AutoCloseable {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val profiles = ProfileStore(context)
        private val originalProfiles = profiles.profiles().map { it.id }.toSet()
        private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        private val previousClip = clipboard.primaryClip
        private val server = MockWebServer()
        private val remoteUncertain = AtomicBoolean(false)
        val uploads = CopyOnWriteArrayList<Upload>()
        val messages = CopyOnWriteArrayList<String>()
        val acknowledgements = CopyOnWriteArrayList<String>()
        val unexpectedInput = CopyOnWriteArrayList<String>()
        val releaseSend = CountDownLatch(1)
        lateinit var vm: HerdrViewModel
        private var profileId: String = ""
        private val url: String

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (request.getHeader("Authorization") != "Bearer $TOKEN") return MockResponse().setResponseCode(401)
                    val path = request.requestUrl!!.encodedPath
                    return when {
                        path == "/v1/health" -> json("""{"service":"herdr-android-bridge","protocol":1,"capabilities":["codex-chat","codex-chat-attachments"]}""")
                        path == "/v1/snapshot" -> json("""{"workspaces":[{"id":"w1","label":"Attachment fixture","cwd":"/tmp/synthetic"}],"tabs":[{"id":"w1:t1","workspaceId":"w1","label":"main"}],"panes":[{"id":"w1:p1","workspaceId":"w1","tabId":"w1:t1","title":"Synthetic Codex","cwd":"/tmp/synthetic","agent":"codex","status":"working","conversation":{"provider":"codex","available":true}}]}""")
                        path.endsWith("/conversation") -> json("""{"binding":"$BINDING","revision":"r${messages.size}","historyEpoch":"synthetic","messages":[{"id":"a1","role":"assistant","text":"Review attachments before sending."}],"unchanged":false,"canSend":${!remoteUncertain.get()},"status":"working","attachmentDeliveryUncertain":${remoteUncertain.get()}}""")
                        path.endsWith("/attachments/acknowledge") -> {
                            assertEquals("POST", request.method)
                            acknowledgements += request.getHeader("X-Herdr-Chat-Binding")!!
                            remoteUncertain.set(false)
                            json("""{"ok":true}""")
                        }
                        path.endsWith("/attachments") && request.method == "POST" -> {
                            assertEquals(BINDING, request.getHeader("X-Herdr-Chat-Binding"))
                            assertEquals("application/octet-stream", request.getHeader("Content-Type"))
                            val bytes = request.body.readByteArray()
                            val id = (uploads.size + 1).toString().padStart(32, '0')
                            uploads += Upload(id, bytes)
                            val kind = request.requestUrl!!.queryParameter("kind")!!
                            val name = request.requestUrl!!.queryParameter("name")!!
                            json(JSONObject().put("id", id).put("name", name).put("kind", kind).put("mimeType", if (kind == "image") "image/png" else "text/plain").put("sizeBytes", bytes.size).toString()).setResponseCode(201)
                        }
                        request.method == "DELETE" && path.contains("/attachments/") -> json("""{"ok":true}""")
                        path.endsWith("/messages") -> {
                            assertEquals("POST", request.method)
                            assertEquals("1", request.getHeader("X-Herdr-Chat-Working-Input"))
                            messages += request.body.readUtf8()
                            releaseSend.await(15, TimeUnit.SECONDS)
                            if (uncertainSend) {
                                remoteUncertain.set(true)
                                json("""{"detail":"Delivery was not confirmed. Check Terminal."}""").setResponseCode(503)
                            } else json("""{"ok":true}""")
                        }
                        else -> { if (request.method != "GET") unexpectedInput += path; MockResponse().setResponseCode(404) }
                    }
                }
            }
            server.start()
            url = server.url("/").toString()
        }

        fun open() {
            compose.onNodeWithContentDescription("Add server").performClick()
            compose.onNode(hasSetTextAction() and hasText("Server name")).performTextInput("Synthetic attachment server")
            compose.onNode(hasSetTextAction() and hasText("Bridge address")).performTextInput(url)
            compose.onNode(hasSetTextAction() and hasText("Access token")).performTextInput(TOKEN)
            compose.onNodeWithText("Save & connect").performClick()
            compose.runOnIdle { vm = ViewModelProvider(compose.activity)[HerdrViewModel::class.java] }
            compose.waitUntil(10_000) { vm.state.value.connection == ConnectionState.CONNECTED && compose.onAllNodesWithContentDescription("Open conversation").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithContentDescription("Open conversation").performClick()
            compose.waitUntil(10_000) { vm.state.value.chat.bindingId == BINDING && vm.state.value.chat.canSend }
            profileId = vm.state.value.activeProfileId!!
        }

        fun target() = ChatAttachmentTarget(profileId, "w1:p1", BINDING)
        fun pasteImage() {
            compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
            compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "Synthetic screenshot", uri("screenshot.png"))) }
            compose.onNodeWithTag("paste-chat-image").performClick()
        }
        fun awaitReady(count: Int) = compose.waitUntil(10_000) { vm.state.value.chat.attachments.size == count && vm.state.value.chat.attachments.all { it.status == ChatAttachmentStatus.READY } }
        override fun close() {
            releaseSend.countDown()
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle {
                ViewModelProvider(compose.activity)[HerdrViewModel::class.java].disconnect()
                if (previousClip != null) clipboard.setPrimaryClip(previousClip) else clipboard.clearPrimaryClip()
            }
            profiles.profiles().filterNot { it.id in originalProfiles }.forEach { profiles.remove(it.id) }
            compose.waitForIdle()
            server.shutdown()
        }
    }

    private fun findComposeEditor(view: View): View? {
        if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") return view
        if (view is ViewGroup) for (i in 0 until view.childCount) findComposeEditor(view.getChildAt(i))?.let { return it }
        return null
    }
    private companion object {
        const val TOKEN = "native-attachments-synthetic-token"
        const val BINDING = "synthetic-attachment-binding"
        fun uri(name: String): Uri = Uri.parse("content://dev.herdr.android.test.synthetic.attachments/$name")
        fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    }
}
