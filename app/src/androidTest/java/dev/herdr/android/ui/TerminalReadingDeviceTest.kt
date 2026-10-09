package dev.herdr.android.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.connectbot.terminal.ScrollController
import org.connectbot.terminal.TerminalEmulatorFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** All output is synthetic; reading gestures must never become remote input. */
@RunWith(AndroidJUnit4::class)
class TerminalReadingDeviceTest {
    @get:Rule val compose = createComposeRule()

    @Test fun defaultWidthKeepsBothOuterColumnsVisibleEvenWithLargeSystemText() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 30, initialCols = 80, onKeyboardInput = {})
        val sent = mutableListOf<ByteArray>()
        compose.setContent {
            HerdrTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                    TerminalSurface(emulator, 30, 80, false, false, 14f, {}, Color.White, Color.Black,
                        { sent += it; true }, Modifier.fillMaxSize())
                }
            }
        }
        compose.runOnIdle {
            emulator.writeInput("\u001B[2J\u001B[1;1H\u001B[42mX\u001B[1;80H\u001B[41mX\u001B[0m\u001B[?25l".toByteArray())
        }
        compose.waitUntil(5_000) {
            val pixels = compose.onNodeWithTag("terminal-surface").captureToImage().toPixelMap()
            val gridRightEdge = emulator.dimensions.widthPixels
            fun containsEdge(start: Int, end: Int, red: Boolean): Boolean = (start until end).any { x ->
                (0 until pixels.height.coerceAtMost(80)).any { y ->
                    val color = pixels[x, y]
                    if (red) color.red > 0.5f && color.green < 0.3f && color.blue < 0.3f
                    else color.green > 0.5f && color.red < 0.3f && color.blue < 0.3f
                }
            }
            // Font hinting can leave a narrow unused right margin. Require the
            // complete native grid to fit and inspect its actual final column,
            // rather than requiring a glyph to touch the phone's outer edge.
            gridRightEdge in 1..pixels.width &&
                containsEdge(0, pixels.width.coerceAtMost(20), false) &&
                containsEdge((gridRightEdge - 20).coerceAtLeast(0), gridRightEdge, true)
        }
        compose.runOnIdle {
            assertEquals(80, emulator.dimensions.columns)
            assertEquals(30, emulator.dimensions.rows)
            assertTrue(sent.isEmpty())
        }
    }

    @Test fun swipeReadsNativeScrollbackAndLiveReturnsToThePromptWithoutSendingAnything() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 5, initialCols = 80, onKeyboardInput = {})
        var controller: ScrollController? = null
        var historyRequests = 0
        val sent = mutableListOf<ByteArray>()
        compose.setContent {
            HerdrTheme {
                TerminalSurface(emulator, 5, 80, true, false, 14f, {}, Color.White, Color.Black,
                    { sent += it; true }, Modifier.fillMaxSize(), onScrollControllerAvailable = { controller = it },
                    onHistoryRequested = { historyRequests++ })
            }
        }
        compose.runOnIdle {
            emulator.writeInput((1..60).joinToString("\r\n") { "Synthetic shell line $it" }.toByteArray())
        }
        compose.waitUntil(5_000) { (controller?.maxScrollback ?: 0) > 30 }
        compose.onNodeWithTag("terminal-surface").performTouchInput {
            swipe(center - Offset(0f, 120f), center + Offset(0f, 120f), 250)
        }
        compose.waitUntil(5_000) { (controller?.scrollbackPosition ?: 0) > 0 }
        compose.onNodeWithTag("terminal-follow-live").assertIsDisplayed().performClick()
        compose.waitUntil(5_000) { controller?.scrollbackPosition == 0 }
        compose.runOnIdle { assertEquals(0, historyRequests); assertTrue(sent.isEmpty()) }
    }

    @Test fun fullScreenProgramSwipeOpensReadOnlyHistoryWithoutInjectingMouseReportsOrKeys() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 30, initialCols = 80, onKeyboardInput = {})
        var controller: ScrollController? = null
        var historyRequests = 0
        var keyboardRequests = 0
        val sent = mutableListOf<ByteArray>()
        compose.setContent {
            HerdrTheme {
                TerminalSurface(emulator, 30, 80, true, false, 14f, {}, Color.White, Color.Black,
                    { sent += it; true }, Modifier.fillMaxSize(), onScrollControllerAvailable = { controller = it },
                    onHistoryRequested = { historyRequests++ }, onKeyboardRequested = { keyboardRequests++ },
                    onKeyInput = { error("A reading gesture must not send a special key") })
            }
        }
        compose.runOnIdle {
            emulator.writeInput("\u001B[?1049h\u001B[?1000h\u001B[?1006h\u001B[2J\u001B[HFullscreen synthetic agent output".toByteArray())
        }
        compose.waitUntil(5_000) { controller != null }
        compose.runOnIdle { assertEquals(0, controller!!.maxScrollback) }
        compose.onNodeWithTag("terminal-surface").performTouchInput {
            swipe(center - Offset(0f, 130f), center + Offset(0f, 130f), 250)
        }
        compose.runOnIdle {
            assertEquals(1, historyRequests)
            assertEquals(0, keyboardRequests)
            assertTrue(sent.isEmpty())
            assertEquals(80, emulator.dimensions.columns)
            assertEquals(30, emulator.dimensions.rows)
        }
    }

    @Test fun advertisedSgrMouseSwipesSendOnlyBoundedWheelReportsAndNeverSendQueriesOrPrompts() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 30, initialCols = 80, onKeyboardInput = {})
        val modes = TerminalInputModes()
        val sent = mutableListOf<ByteArray>()
        var historyRequests = 0
        compose.setContent {
            HerdrTheme {
                TerminalSurface(emulator, 30, 80, true, false, 14f, {}, Color.White, Color.Black,
                    { sent += it; true }, Modifier.fillMaxSize(), inputModes = modes,
                    onHistoryRequested = { historyRequests++ }, onKeyInput = { error("SGR scrolling must not submit keys") })
            }
        }
        compose.runOnIdle {
            val frame = "\u001B[?1049h\u001B[?1000;1006h\u001B[2J\u001B[HSGR synthetic output\u001B[6n\u001B[c".toByteArray()
            modes.observe(frame, reset = true)
            emulator.writeInput(frame)
            assertTrue(sent.isEmpty())
        }
        compose.onNodeWithTag("terminal-surface").performTouchInput {
            swipe(Offset(center.x, 20f), Offset(center.x, height - 20f), 700)
        }
        compose.runOnIdle {
            val reports = sent.joinToString("") { it.toString(Charsets.US_ASCII) }
            val expected = "\u001B[<64;1;1M"
            val count = reports.length / expected.length
            assertTrue("One drag produces a bounded number of wheel reports", count in 1..12)
            assertEquals(expected.repeat(count), reports)
            assertEquals(0, historyRequests)
            sent.clear()
        }
        compose.onNodeWithTag("terminal-surface").performTouchInput {
            swipe(Offset(center.x, height - 20f), Offset(center.x, 20f), 700)
        }
        compose.runOnIdle {
            val reports = sent.joinToString("") { it.toString(Charsets.US_ASCII) }
            val expected = "\u001B[<65;1;1M"
            val count = reports.length / expected.length
            assertTrue(count in 1..12)
            assertEquals(expected.repeat(count), reports)
            assertEquals(80, emulator.dimensions.columns)
            assertEquals(30, emulator.dimensions.rows)
        }
    }

    @Test fun refusedWheelDeliveryStopsFurtherWritesWithinTheGesture() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 30, initialCols = 80, onKeyboardInput = {})
        val modes = TerminalInputModes().apply { observe("\u001B[?1003;1006h".toByteArray()) }
        var attempts = 0
        compose.setContent {
            HerdrTheme {
                TerminalSurface(emulator, 30, 80, true, false, 14f, {}, Color.White, Color.Black,
                    { attempts++; false }, Modifier.fillMaxSize(), inputModes = modes)
            }
        }
        compose.onNodeWithTag("terminal-surface").performTouchInput {
            swipe(Offset(center.x, 20f), Offset(center.x, height - 20f), 700)
        }
        compose.runOnIdle { assertEquals(1, attempts) }
    }

    @Test fun pageScrollRequiresOptInAndInputPermissionAndStopsWhenTurnedOff() {
        val emulator = TerminalEmulatorFactory.create(initialRows = 30, initialCols = 80, onKeyboardInput = {})
        var optedIn by mutableStateOf(false)
        var enabled by mutableStateOf(true)
        var historyRequests = 0
        val directions = mutableListOf<Boolean>()
        compose.setContent {
            HerdrTheme {
                TerminalSurface(emulator, 30, 80, enabled, false, 14f, {}, Color.White, Color.Black,
                    { error("Page scroll must use logical keys") }, Modifier.fillMaxSize(),
                    scrollProgram = optedIn, onProgramScroll = { directions += it; true }, onHistoryRequested = { historyRequests++ })
            }
        }
        fun olderSwipe() {
            compose.onNodeWithTag("terminal-surface").performTouchInput {
                swipe(center - Offset(0f, 180f), center + Offset(0f, 180f), 500)
            }
        }
        olderSwipe()
        compose.runOnIdle { assertTrue(directions.isEmpty()); assertEquals(1, historyRequests); optedIn = true }
        olderSwipe()
        compose.runOnIdle { assertTrue(directions.size in 1..3); assertTrue(directions.all { it }); directions.clear(); enabled = false }
        olderSwipe()
        compose.runOnIdle { assertTrue(directions.isEmpty()); enabled = true; optedIn = false }
        olderSwipe()
        compose.runOnIdle { assertTrue(directions.isEmpty()) }
    }

    @Test fun fitWidthUpgradeAndLaterExplicitZoomPreferencesAreRespected() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("terminal_appearance", android.content.Context.MODE_PRIVATE)
        val original = preferences.all.toMap()
        try {
            preferences.edit().clear().putFloat("font_size", 18f).putBoolean("fit_width", false).commit()
            val store = TerminalPreferenceStore(context)
            assertTrue(store.read().fitToWidth)
            assertEquals(18f, store.read().fontSize, 0f)
            store.write(store.read().copy(fitToWidth = false, fontSize = 20f))
            assertFalse(store.read().fitToWidth)
            assertEquals(20f, store.read().fontSize, 0f)
            store.write(store.read().copy(fitToWidth = true))
            assertTrue(store.read().fitToWidth)
        } finally {
            val restore = preferences.edit().clear()
            original.forEach { (key, value) ->
                when (value) {
                    is String -> restore.putString(key, value)
                    is Boolean -> restore.putBoolean(key, value)
                    is Float -> restore.putFloat(key, value)
                    is Int -> restore.putInt(key, value)
                    is Long -> restore.putLong(key, value)
                }
            }
            restore.commit()
        }
    }
}
