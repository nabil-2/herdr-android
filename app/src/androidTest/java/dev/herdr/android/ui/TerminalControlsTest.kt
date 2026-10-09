package dev.herdr.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.semantics.SemanticsProperties
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalControlsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun typingSendsNothingUntilExplicitSendAndOnlySuccessClearsTheDraft() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        var inputRevision by mutableLongStateOf(0L)
        var sending by mutableStateOf(false)
        compose.setContent {
            HerdrTheme {
                CommandComposer("pane-1", enabled = !sending, sending = sending, inputRevision = inputRevision) { text, enter ->
                    sent += text to enter
                    sending = true
                }
            }
        }
        compose.onNodeWithTag("send-command").assertIsNotEnabled()
        compose.onNodeWithTag("command-draft").performTextInput("Explain this repository")
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithTag("send-command").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(listOf("Explain this repository" to true), sent)
            sending = false // A failed or uncertain write produces no acknowledgement.
        }
        compose.onNodeWithTag("command-draft").assertTextEquals("Explain this repository")
        compose.onNodeWithTag("send-command").performClick()
        compose.runOnIdle { inputRevision++; sending = false }
        compose.onNodeWithTag("command-draft").assert(SemanticsMatcher("The acknowledged command draft is empty") { node ->
            SemanticsProperties.EditableText in node.config && node.config[SemanticsProperties.EditableText].text.isEmpty()
        })
    }

    @Test
    fun enterToggleControlsWhetherSendSubmitsTheCommand() {
        val sent = mutableListOf<Pair<String, Boolean>>()
        compose.setContent {
            HerdrTheme { CommandComposer("pane-1", true, false, 0L) { text, enter -> sent += text to enter } }
        }
        compose.onNodeWithTag("command-draft").performTextInput("literal text")
        compose.onNodeWithTag("append-enter").performClick()
        compose.onNodeWithTag("send-command").performClick()
        compose.runOnIdle { assertEquals(listOf("literal text" to false), sent) }
    }

    @Test
    fun specialKeysAreExplicitAndLeaveTheCommandDraftUntouched() {
        val keys = mutableListOf<String>()
        compose.setContent {
            HerdrTheme {
                Column {
                    SpecialKeyBar(true) { keys += it }
                    CommandComposer("pane-1", true, false, 0L) { _, _ -> }
                }
            }
        }
        compose.onNodeWithTag("command-draft").performTextInput("unfinished prompt")
        compose.onNodeWithTag("key-enter").performClick()
        compose.onNodeWithTag("key-esc").performClick()
        compose.onNodeWithTag("key-tab").performClick()
        compose.runOnIdle { assertEquals(listOf("enter", "esc", "tab"), keys) }
        compose.onNodeWithTag("command-draft").assertTextEquals("unfinished prompt")
    }

    @Test
    fun disconnectedControlsCannotSendRemoteInput() {
        var writes = 0
        compose.setContent {
            HerdrTheme {
                Column {
                    SpecialKeyBar(false) { writes++ }
                    CommandComposer("pane-1", false, false, 0L) { _, _ -> writes++ }
                }
            }
        }
        compose.onNodeWithTag("command-draft").performTextInput("echo safe")
        compose.onNodeWithTag("send-command").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("key-enter").assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(0, writes) }
    }

    @Test
    fun nativeLibvtermProcessesAnsiAndKeepsObserverGeometry() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 30, initialCols = 80)
        compose.setContent {
            HerdrTheme { Terminal(emulator, Modifier.fillMaxSize(), keyboardEnabled = false, forcedSize = 30 to 80) }
        }
        compose.runOnIdle {
            emulator.writeInput("\u001B[2J\u001B[3;5H\u001B[32mhttps://example.com/native\u001B[0m".toByteArray())
        }
        compose.waitUntil(timeoutMillis = 5_000) { emulator.getUrls().any { it.url == "https://example.com/native" } }
        compose.runOnIdle {
            assertEquals(30, emulator.dimensions.rows)
            assertEquals(80, emulator.dimensions.columns)
        }
    }
}
