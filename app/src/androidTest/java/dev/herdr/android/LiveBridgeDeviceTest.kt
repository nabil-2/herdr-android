package dev.herdr.android

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.herdr.android.data.ProfileStore
import dev.herdr.android.data.PairingPayload
import dev.herdr.android.ui.decodePairingQr
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in end-to-end test against scripts/live_device_fixture.py's own session. */
@RunWith(AndroidJUnit4::class)
class LiveBridgeDeviceTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun nativeDecoderReadsTheHostGeneratedPairingQr() {
        val args = InstrumentationRegistry.getArguments()
        val payload = args.getString("live_pairing_payload")
        val path = args.getString("live_pairing_qr")
        assumeTrue("Provide fixture QR and payload", payload != null && path != null)
        val bitmap = android.graphics.BitmapFactory.decodeFile(path!!)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val luminance = ByteArray(pixels.size) { index ->
            val color = pixels[index]
            ((android.graphics.Color.red(color) * 299 + android.graphics.Color.green(color) * 587 +
                android.graphics.Color.blue(color) * 114) / 1000).toByte()
        }
        val decoded = decodePairingQr(luminance, bitmap.width, bitmap.height)
        assertEquals(payload, decoded)
        assertEquals("Android live test", PairingPayload.parse(decoded!!).name)
        bitmap.recycle()
    }

    @Test
    fun phonePairsWithAOneUseCodeAndControlsTheIsolatedHerdrPane() {
        val payload = InstrumentationRegistry.getArguments().getString("live_pairing_payload")
        assumeTrue("Start the live fixture and provide its pairing payload", payload != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProfileStore(context)
        val originalIds = store.profiles().map { it.id }.toSet()
        try {
            compose.onNodeWithText("Paste pairing code").performClick()
            compose.onNodeWithTag("pairing-payload").performTextInput(payload!!)
            compose.onNodeWithTag("review-pairing").performClick()
            compose.onNodeWithText("Connect to Android live test").assertIsDisplayed()
            val screenshots = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
            compose.waitForIdle()
            File(screenshots, "pairing.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithTag("confirm-pairing").performScrollTo().performClick()
            compose.waitUntil(timeoutMillis = 20_000) {
                compose.onAllNodesWithContentDescription("Open terminal").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Connecting to your server…").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("Open terminal").performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithText("Live", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("command-draft").performTextInput("printf 'PHONE_QR_PAIRING_E2E_OK\\n'")
            compose.onNodeWithTag("send-command").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                val node = compose.onNodeWithTag("command-draft").fetchSemanticsNode()
                node.config.getOrElse(SemanticsProperties.EditableText) { AnnotatedString("pending") }.text.isEmpty()
            }
        } finally {
            store.profiles().filterNot { it.id in originalIds }.forEach { store.remove(it.id) }
        }
    }

    @Test
    fun nativePhoneConnectsToAnIsolatedHerdrAndSendsAnExplicitCommand() {
        val args = InstrumentationRegistry.getArguments()
        val address = args.getString("live_bridge_url")
        val token = args.getString("live_bridge_token")
        assumeTrue("Start the isolated live fixture and provide live_bridge_url/token", address != null && token != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = ProfileStore(context)
        val originalIds = store.profiles().map { it.id }.toSet()
        try {
            compose.onNodeWithContentDescription("Add server").performClick()
            compose.onNode(hasSetTextAction() and hasText("Server name")).performTextInput("Emulator live test")
            compose.onNode(hasSetTextAction() and hasText("Bridge address")).performTextInput(address!!)
            compose.onNode(hasSetTextAction() and hasText("Access token")).performTextInput(token!!)
            compose.onNodeWithText("Save & connect").performClick()
            compose.waitUntil(timeoutMillis = 20_000) {
                compose.onAllNodesWithText("Android live test").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithContentDescription("Open terminal").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("Connecting to your server…").fetchSemanticsNodes().isEmpty()
            }
            compose.onNodeWithContentDescription("Open terminal").assertIsDisplayed()
            compose.waitForIdle()
            InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(250L, 5_000L)
            val screenshots = File(context.getExternalFilesDir(null), "validation").apply { mkdirs() }
            File(screenshots, "dashboard.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            compose.onNodeWithContentDescription("Open terminal").performClick()
            compose.waitUntil(timeoutMillis = 15_000) {
                compose.onAllNodesWithText("Live", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("command-draft").performTextInput("printf 'PHONE_NATIVE_E2E_OK\\n'")
            compose.onNodeWithTag("send-command").performClick()
            compose.waitUntil(timeoutMillis = 10_000) {
                val node = compose.onNodeWithTag("command-draft").fetchSemanticsNode()
                node.config.getOrElse(SemanticsProperties.EditableText) { AnnotatedString("pending") }.text.isEmpty()
            }
            compose.onNodeWithTag("command-draft").assert(
                SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")),
            )
            compose.waitForIdle()
            File(screenshots, "terminal.png").outputStream().use {
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
                    .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally {
            store.profiles().filterNot { it.id in originalIds }.forEach { store.remove(it.id) }
        }
    }
}
