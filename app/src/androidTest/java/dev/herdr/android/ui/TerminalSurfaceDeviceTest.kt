package dev.herdr.android.ui

import android.view.KeyEvent
import android.content.ClipboardManager
import android.content.ClipData
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.connectbot.terminal.SelectionController
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalSurfaceDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun nativeImeCompositionAndHardwareControlsGenerateOnlyExplicitInput() {
        val sent = mutableListOf<ByteArray>()
        var consumed = 0
        compose.runOnUiThread {
            val input = TerminalInputView(InstrumentationRegistry.getInstrumentation().targetContext).apply {
                onInput = { sent += it; true }
                onModifierConsumed = { consumed++ }
                configure(enabled = true, showKeyboard = false)
            }
            val connection = input.onCreateInputConnection(EditorInfo())!!
            assertTrue(connection.setComposingText("caf", 1))
            assertTrue(connection.setComposingText("café", 1))
            assertTrue(sent.isEmpty())
            assertTrue(connection.commitText("café 🐚", 1))
            assertArrayEquals("café 🐚".toByteArray(), sent.single())
            assertTrue(connection.deleteSurroundingText(1, 0))
            assertArrayEquals(byteArrayOf(127), sent.last())
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
            assertArrayEquals(byteArrayOf(13), sent.last())
            input.ctrlPressed = true
            assertTrue(connection.commitText("c", 1))
            assertArrayEquals(byteArrayOf(3), sent.last())
            input.ctrlPressed = false
            assertTrue(connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_CTRL_ON)))
            assertArrayEquals("\u001B[1;5D".toByteArray(), sent.last())
            assertEquals(4, consumed)
            input.dispose()
        }
    }

    @Test
    fun disabledOrRefusedTypingNeverClearsCompositionOrConsumesModifiers() {
        var writes = 0
        var consumed = 0
        var composing = ""
        compose.runOnUiThread {
            val input = TerminalInputView(InstrumentationRegistry.getInstrumentation().targetContext).apply {
                onInput = { writes++; false }
                onModifierConsumed = { consumed++ }
                onComposingChanged = { composing = it }
                configure(enabled = true, showKeyboard = false)
            }
            val connection = input.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("retained", 1)
            assertFalse(connection.finishComposingText())
            assertEquals("retained", composing)
            assertEquals(0, consumed)
            input.configure(enabled = false, showKeyboard = false)
            assertFalse(connection.commitText("late input", 1))
            assertEquals(1, writes)
            assertEquals("", composing)
            input.configure(enabled = true, showKeyboard = false)
            assertFalse("An old connection stays invalid after re-enabling the view", connection.commitText("stale input", 1))
            assertEquals(1, writes)
            val fresh = input.onCreateInputConnection(EditorInfo())!!
            input.onInput = { writes++; true }
            assertTrue(fresh.commitText("fresh", 1))
            assertEquals(2, writes)
            input.onWindowFocusChanged(false)
            assertFalse("A callback from the terminal behind a modal cannot write", fresh.commitText("behind modal", 1))
            assertEquals(2, writes)
            input.dispose()
        }
    }

    @Test
    fun logicalNavigationAndEnterFlushCompositionAndDeletionStaysLocal() {
        val actions = mutableListOf<String>()
        var composing = ""
        compose.runOnUiThread {
            val input = TerminalInputView(InstrumentationRegistry.getInstrumentation().targetContext).apply {
                onInput = { actions += "text:${it.toString(Charsets.UTF_8)}"; true }
                onKeyInput = { actions += "keys:${it.joinToString(",")}"; true }
                onComposingChanged = { composing = it }
                configure(enabled = true, showKeyboard = false)
            }
            val connection = input.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("ls🐚", 1)
            assertTrue(connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)))
            assertEquals("ls", composing)
            assertTrue(actions.isEmpty())
            assertTrue(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
            assertEquals(listOf("text:ls", "keys:enter"), actions)
            connection.setComposingText("xy", 1)
            assertTrue(connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, 0, KeyEvent.META_CTRL_ON)))
            assertEquals(listOf("text:ls", "keys:enter", "text:xy", "keys:ctrl+left"), actions)
            assertTrue(connection.deleteSurroundingText(2, 1))
            assertEquals("keys:backspace,backspace,delete", actions.last())
            connection.setComposingText("\n", 1)
            assertTrue(connection.finishComposingText())
            assertEquals("keys:enter", actions.last())
            assertEquals(6, actions.size)
            connection.setComposingText("plain pending word", 1)
            input.ctrlPressed = true
            assertTrue(input.sendKey("ctrl+left"))
            assertEquals(listOf("text:plain pending word", "keys:ctrl+left"), actions.takeLast(2))
            assertFalse(input.ctrlPressed)
            connection.setComposingText("must not be sent", 1)
            assertTrue(input.sendKey("ctrl+c"))
            assertEquals("keys:ctrl+c", actions.last())
            assertEquals("", composing)
            input.dispose()
        }
    }

    @Test
    fun refusedCompositionPreventsEnterAndKeepsTextForReview() {
        var keyWrites = 0
        var composing = ""
        compose.runOnUiThread {
            val input = TerminalInputView(InstrumentationRegistry.getInstrumentation().targetContext).apply {
                onInput = { false }
                onKeyInput = { keyWrites++; true }
                onComposingChanged = { composing = it }
                configure(enabled = true, showKeyboard = false)
            }
            val connection = input.onCreateInputConnection(EditorInfo())!!
            connection.setComposingText("not accepted", 1)
            assertFalse(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
            assertEquals("not accepted", composing)
            assertEquals(0, keyWrites)
            input.dispose()
        }
    }

    @Test
    fun imeClipboardAndHardwarePasteRequirePreviewWhileEnterRemainsDirect() {
        val sent = mutableListOf<ByteArray>()
        var preview: String? = null
        var pasteRequests = 0
        compose.runOnUiThread {
            val input = TerminalInputView(InstrumentationRegistry.getInstrumentation().targetContext).apply {
                onInput = { sent += it; true }
                onPasteTextRequest = { preview = it }
                onPasteRequest = { pasteRequests++ }
                configure(enabled = true, showKeyboard = false)
            }
            val connection = input.onCreateInputConnection(EditorInfo())!!
            assertTrue(connection.commitText("echo one\necho two\n", 1))
            assertEquals("echo one\necho two\n", preview)
            assertTrue(sent.isEmpty())
            connection.setComposingText("echo composed\n", 1)
            assertFalse(connection.performEditorAction(EditorInfo.IME_ACTION_DONE))
            assertEquals("echo composed\n", preview)
            assertTrue(sent.isEmpty())
            assertTrue(connection.performContextMenuAction(android.R.id.paste))
            assertTrue(connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_V, 0, KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON)))
            assertEquals(2, pasteRequests)
            assertTrue(connection.commitText("\n", 1))
            assertArrayEquals(byteArrayOf(13), sent.single())
            input.dispose()
        }
    }

    @Test
    fun zoomSelectionAndRemoteQueriesKeepTheObserverGridAndNeverSendInput() {
        val sent = mutableListOf<ByteArray>()
        var fontSize by mutableStateOf(14f)
        var selection: SelectionController? = null
        val emulator = TerminalEmulatorFactory.create(initialRows = 30, initialCols = 80, onKeyboardInput = {})
        compose.setContent {
            HerdrTheme {
                TerminalSurface(
                    emulator, 30, 80, enabled = true, showSoftKeyboard = false,
                    fontSize = fontSize, onFontSizeChange = { fontSize = it },
                    foreground = Color.White, background = Color.Black,
                    onInput = { sent += it; true }, modifier = Modifier.fillMaxSize(),
                    onSelectionControllerAvailable = { selection = it },
                )
            }
        }
        compose.runOnIdle {
            emulator.writeInput("\u001B[2J\u001B[1;1HLOCAL SYNTHETIC FIXTURE\u001B[6n\u001B[c\u001B]52;c;aWdub3Jl\u0007".toByteArray())
        }
        compose.waitUntil(5_000) { selection != null }
        compose.onNodeWithTag("terminal-surface").performTouchInput {
            val middle = center
            down(0, middle + Offset(-50f, 0f))
            down(1, middle + Offset(50f, 0f))
            moveTo(0, middle + Offset(-110f, 0f), delayMillis = 32)
            moveTo(1, middle + Offset(110f, 0f), delayMillis = 32)
            up(0)
            up(1)
        }
        compose.runOnIdle {
            assertTrue("Pinch changes the persistent font size", fontSize > 14f)
            assertEquals(30, emulator.dimensions.rows)
            assertEquals(80, emulator.dimensions.columns)
            selection!!.selectAllVisible()
            assertTrue(sent.isEmpty())
        }
        compose.onNodeWithTag("terminal-copy-selection").performClick()
        compose.runOnIdle {
            val clipboard = InstrumentationRegistry.getInstrumentation().targetContext.getSystemService(ClipboardManager::class.java)
            assertTrue(clipboard.primaryClip!!.getItemAt(0).text.toString().contains("LOCAL SYNTHETIC FIXTURE"))
            clipboard.setPrimaryClip(ClipData.newPlainText("", ""))
        }
        compose.onNodeWithTag("terminal-copy-selection").assertDoesNotExist()
    }

    @Test
    fun localPanIsBoundedToTheRemoteGrid() {
        assertEquals(Offset(-400f, -200f), clampTerminalPan(Offset(-900f, -500f), IntSize(800, 600), IntSize(400, 400)))
        assertEquals(Offset.Zero, clampTerminalPan(Offset(500f, 400f), IntSize(800, 600), IntSize(400, 400)))
        assertEquals(Offset.Zero, clampTerminalPan(Offset(-100f, -100f), IntSize(300, 300), IntSize(400, 400)))
    }
}
