package dev.herdr.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalExperienceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun multilinePasteOpensComposeInsteadOfExecutingRemoteCommands() {
        val inserted = mutableListOf<String>()
        val drafted = mutableListOf<String>()
        compose.setContent { HerdrTheme { TerminalPasteReview("echo first\necho second", true, inserted::add, drafted::add, {}) } }
        compose.onNodeWithTag("terminal-confirm-paste").assertIsEnabled().performClick()
        compose.runOnIdle {
            assertTrue(inserted.isEmpty())
            assertEquals(listOf("echo first\necho second"), drafted)
        }
    }

    @Test fun pasteWithEscapeSequencesCannotBeInsertedAndCancelSendsNothing() {
        var writes = 0
        var cancelled = false
        compose.setContent { HerdrTheme { TerminalPasteReview("\u001B[31munsafe", true, { writes++ }, { writes++ }, { cancelled = true }) } }
        compose.onNodeWithTag("terminal-confirm-paste").assertIsNotEnabled().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, writes); assertTrue(cancelled) }
    }

    @Test fun hidingComposerRetainsUnsentDraftAndOnlyAcknowledgementClearsIt() {
        val state = TerminalDraftState()
        var showing by mutableStateOf(true)
        var revision by mutableLongStateOf(0)
        val sent = mutableListOf<String>()
        compose.setContent {
            HerdrTheme { if (showing) CommandComposer("pane", true, false, revision, draftState = state, onSend = { text, _ -> sent += text }) }
        }
        compose.onNodeWithTag("command-draft").performTextInput("unfinished command")
        compose.runOnIdle { showing = false }
        compose.runOnIdle { showing = true }
        compose.onNodeWithTag("command-draft").assertTextEquals("unfinished command")
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
        compose.onNodeWithTag("send-command").performClick()
        compose.runOnIdle { showing = false; revision++ }
        compose.runOnIdle { showing = true }
        compose.runOnIdle { assertEquals("", state.text.value) }
    }

    @Test fun reusableSnippetIsInsertedForReviewAndNeverSendsOnSelection() {
        val memory = TerminalCommandMemory().apply { saveSnippet("Check changes", "git status --short") }
        var writes = 0
        compose.setContent { HerdrTheme { TerminalComposeSheet("pane", true, false, 0, memory, {}, { _, _ -> writes++ }, {}) } }
        compose.onNodeWithText("Check changes").performScrollTo().performClick()
        compose.onNodeWithTag("command-draft").performScrollTo().assertTextEquals("git status --short")
        compose.runOnIdle { assertEquals(0, writes) }
        compose.onNodeWithTag("send-command").performClick()
        compose.runOnIdle { assertEquals(1, writes) }
    }

    @Test fun singleLinePasteRequiresConfirmationAndDoesNotAppendEnter() {
        val inserted = mutableListOf<String>()
        compose.setContent { HerdrTheme { TerminalPasteReview("echo review first", true, inserted::add, {}, {}) } }
        compose.runOnIdle { assertTrue(inserted.isEmpty()) }
        compose.onNodeWithTag("terminal-confirm-paste").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(listOf("echo review first"), inserted) }
    }
}
