package dev.herdr.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.ClipData
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.data.TerminalHistory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalOutputTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loadedHistoryCanBeSearchedNavigatedAndCopiedWithoutChangingItsText() {
        val text = "begin\nrepeated phrase\nmiddle\nrepeated phrase\nend"
        compose.setContent { HerdrTheme { TerminalOutputSheet("pane", { TerminalHistory("pane", text, false) }, {}) } }
        compose.onNodeWithTag("terminal-output-text").assertTextEquals(text)
        compose.onNodeWithTag("terminal-output-find").performTextInput("repeated")
        compose.onNodeWithTag("terminal-output-match-count").assertTextEquals("1 of 2")
        compose.onNodeWithTag("terminal-output-next").performClick()
        compose.onNodeWithTag("terminal-output-match-count").assertTextEquals("2 of 2")
        compose.onNodeWithTag("terminal-output-previous").performClick()
        compose.onNodeWithTag("terminal-output-match-count").assertTextEquals("1 of 2")
        compose.onNodeWithTag("terminal-output-copy").performClick()
        compose.runOnIdle {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            assertEquals(text, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
    }

    @Test fun failedReadCanRetryAndBackgroundingClearsLoadedOutput() {
        val owner = OutputLifecycleOwner()
        var attempts = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                HerdrTheme { TerminalOutputSheet("pane", {
                    attempts++
                    if (attempts == 1) throw IllegalStateException("Synthetic offline fixture")
                    TerminalHistory("pane", "recent synthetic output", true)
                }, {}) }
            }
        }
        compose.onNodeWithTag("terminal-output-error").assertExists()
        compose.onNodeWithTag("terminal-output-refresh").performClick()
        compose.onNodeWithTag("terminal-output-text").assertTextEquals("recent synthetic output")
        compose.onNodeWithText("Recent output; older text omitted").assertExists()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.STARTED }
        compose.onNodeWithTag("terminal-output-copy").assertIsNotEnabled()
        compose.onNodeWithTag("terminal-output-text").assertDoesNotExist()
        compose.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithTag("terminal-output-text").assertTextEquals("recent synthetic output")
        compose.runOnIdle { assertEquals(3, attempts) }
    }

    private class OutputLifecycleOwner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
}
