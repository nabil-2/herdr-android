package dev.herdr.android.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.herdr.android.data.ChatContent
import dev.herdr.android.data.ChatMessage
import dev.herdr.android.data.ConnectionState
import dev.herdr.android.data.HerdrUiState
import dev.herdr.android.data.PaneMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeChatUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun multilineDraftRequiresExplicitSendAndRemainsEditableWhileSending() {
        var draft by mutableStateOf("")
        var sending by mutableStateOf(false)
        val sent = mutableListOf<String>()
        compose.setContent {
            HerdrTheme {
                ChatComposer(draft, canSend = true, sending = sending, onDraftChange = { draft = it }, onSend = { sent += draft; sending = true })
            }
        }
        compose.onNodeWithTag("send-chat").assertIsNotEnabled()
        compose.onNodeWithTag("chat-draft").performTextInput("Explain the change\nand its tests")
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithTag("send-chat").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("Explain the change\nand its tests"), sent) }
        compose.onNodeWithTag("chat-draft").performTextInput("\nFollow-up draft")
        compose.onNodeWithTag("chat-draft").assertTextEquals("Explain the change\nand its tests\nFollow-up draft")
        compose.onNodeWithTag("send-chat").assertIsNotEnabled()
    }

    @Test
    fun blockedConversationHasATerminalActionWithoutCommandControlsEvenAtLargeFont() {
        var opened = 0
        compose.setContent {
            HerdrTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                    ChatScreen(
                        state = HerdrUiState(connection = ConnectionState.CONNECTED, paneMode = PaneMode.CHAT,
                            chat = ChatContent(bindingId = "binding", canSend = true, status = "blocked", draft = "Please continue")),
                        onBack = {}, onTerminal = { opened++ }, onRetry = {}, onOlder = {}, onDraftChange = {}, onSend = {}, onAcknowledgeDelivery = {},
                    )
                }
            }
        }
        compose.onNodeWithTag("chat-attention").assertIsDisplayed()
        compose.onNodeWithTag("send-chat").assertIsNotEnabled()
        compose.onNodeWithTag("key-enter").assertDoesNotExist()
        compose.onNodeWithTag("command-draft").assertDoesNotExist()
        compose.onNodeWithTag("open-chat-terminal").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun transcriptContainsOnlyMessagesAndUserTextStaysLiteral() {
        val chat = ChatContent(bindingId = "binding", messages = listOf(
            ChatMessage("user", "user", "**Literal input**"),
            ChatMessage("tool", "tool", "SECRET_TOOL_RESULT"),
            ChatMessage("assistant", "assistant", "Readable answer"),
        ))
        compose.setContent { HerdrTheme { ConversationMessages(chat, {}, Modifier.fillMaxSize()) } }
        compose.onNodeWithText("**Literal input**").assertIsDisplayed()
        compose.onNodeWithText("SECRET_TOOL_RESULT").assertDoesNotExist()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Readable answer").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Readable answer").assertIsDisplayed()
    }

    @Test
    fun assistantMarkdownRendersNativeCodeAndCopyPreservesIndentation() {
        val message = ChatMessage("markdown", "assistant", "# Result\n\nA **native** answer.\n\n```python\ndef example():\n    return 42\n```\n\n| Item | State |\n|---|---|\n| Tests | Passed |")
        compose.setContent { HerdrTheme { ConversationMessages(ChatContent(bindingId = "markdown", messages = listOf(message)), {}, Modifier.fillMaxSize()) } }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Copy code").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Result").performScrollTo().assertIsDisplayed()
        compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
        compose.onNodeWithText("Copy code").performScrollTo().assertIsDisplayed().performClick()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val expected = "def example():\n    return 42"
        compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.contains(expected) == true }
        compose.runOnIdle { assertTrue(clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.contains(expected) == true) }
        compose.onNodeWithText("Passed").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun linksOpenOnlyAfterTapAndUnsupportedSchemesStayInsideTheApp() {
        val opened = mutableListOf<String>()
        compose.setContent {
            HerdrTheme {
                CompositionLocalProvider(LocalUriHandler provides object : UriHandler { override fun openUri(uri: String) { opened += uri } }) {
                    AssistantMarkdown("[Open docs](https://example.com/docs)\n\n[Unsafe](file:///private/file)")
                }
            }
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Open docs").fetchSemanticsNodes().isNotEmpty() }
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        compose.onNodeWithText("Unsafe").performClick()
        compose.runOnIdle { assertTrue(opened.isEmpty()) }
        compose.onNodeWithText("Open docs").performClick()
        compose.runOnIdle { assertEquals(listOf("https://example.com/docs"), opened) }
    }

    @Test
    fun newMessagesAndEarlierPagesPreserveTheReadersAnchor() {
        var chat by mutableStateOf(ChatContent(bindingId = "binding", olderCursor = "older", messages = (1..40).map {
            ChatMessage("m$it", "user", "Message $it\nA second line to make scrolling necessary.")
        }))
        var olderRequests = 0
        compose.setContent { HerdrTheme { Box(Modifier.fillMaxSize()) { ConversationMessages(chat, { olderRequests++ }, Modifier.fillMaxSize()) } } }
        compose.onNodeWithTag("chat-messages").performScrollToNode(hasTestTag("chat-message-m20"))
        compose.onNodeWithTag("chat-message-m20").assertIsDisplayed()
        compose.runOnIdle { chat = chat.copy(messages = chat.messages + ChatMessage("new", "user", "New message")) }
        compose.onNodeWithTag("chat-message-m20").assertIsDisplayed()
        compose.onNodeWithTag("chat-messages").performScrollToNode(hasTestTag("load-older-messages"))
        compose.onNodeWithTag("load-older-messages").performClick()
        compose.runOnIdle {
            assertEquals(1, olderRequests)
            chat = chat.copy(messages = listOf(ChatMessage("earlier", "user", "Earlier message")) + chat.messages)
        }
        compose.onNodeWithTag("load-older-messages").assertIsDisplayed()
    }

    @Test
    fun loadingEarlierFromAShortPageKeepsTheReaderAtTheEarlierMessages() {
        var chat by mutableStateOf(ChatContent(bindingId = "binding", olderCursor = "older", messages = listOf(ChatMessage("latest", "user", "Latest message"))))
        compose.setContent {
            HerdrTheme {
                ConversationMessages(chat, {
                    chat = chat.copy(messages = (1..30).map { ChatMessage("earlier$it", "user", "Earlier message $it\nSecond line") } + chat.messages)
                }, Modifier.fillMaxSize())
            }
        }
        compose.onNodeWithTag("load-older-messages").assertIsDisplayed().performClick()
        compose.onNodeWithTag("load-older-messages").assertIsDisplayed()
        compose.onNodeWithTag("chat-message-earlier1").assertIsDisplayed()
    }
}
