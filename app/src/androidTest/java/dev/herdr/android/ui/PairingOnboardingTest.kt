package dev.herdr.android.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
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
class PairingOnboardingTest {
    @get:Rule val compose = createComposeRule()
    private val payload = """{"type":"herdr-pocket-pair","version":1,"url":"https://dust.example.ts.net","name":"Dust","code":"${"A".repeat(43)}","expiresAt":9223372036854775807}"""

    @Test
    fun pastedCodeRequiresServerReviewAndExplicitConfirmation() {
        val submitted = mutableListOf<String>()
        var pairing by mutableStateOf(false)
        compose.setContent {
            HerdrTheme {
                PairingScreen(PairingEntryMode.PASTE, false, false, pairing, null, {}, {
                    submitted += it
                    pairing = true
                }, {}, {})
            }
        }
        compose.onNodeWithTag("pairing-payload").performTextInput(payload)
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }
        compose.onNodeWithTag("review-pairing").performClick()
        compose.onNodeWithText("Connect to Dust").assertIsDisplayed()
        compose.onNodeWithTag("pairing-host").assertIsDisplayed()
        compose.runOnIdle { assertTrue(submitted.isEmpty()) }
        compose.onNodeWithTag("confirm-pairing").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(payload), submitted) }
        compose.onNodeWithTag("confirm-pairing").assertIsNotEnabled()
    }

    @Test
    fun malformedQrNeverStartsPairingOrShowsAConfirmation() {
        var submitted = 0
        compose.setContent {
            HerdrTheme { PairingScreen(PairingEntryMode.PASTE, false, false, false, null, {}, { submitted++ }, {}, {}) }
        }
        compose.onNodeWithTag("pairing-payload").performTextInput("https://example.com/not-a-pairing-code")
        compose.onNodeWithTag("review-pairing").performClick()
        compose.onNodeWithText("This is not a valid Herdr pairing QR code. Generate a new code on your server.").assertIsDisplayed()
        compose.onNodeWithTag("confirm-pairing").assertDoesNotExist()
        compose.runOnIdle { assertEquals(0, submitted) }
    }

    @Test
    fun cameraDenialOffersPasteAndRequestsCameraOnlyAfterScanIsTapped() {
        var cameraRequests = 0
        var submissions = 0
        compose.setContent {
            HerdrTheme { PairingScreen(PairingEntryMode.SCAN, false, true, false, null, { cameraRequests++ }, { submissions++ }, {}, {}) }
        }
        compose.onNodeWithText("Camera access is off").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, cameraRequests) }
        compose.onNodeWithText("Scan pairing code").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cameraRequests) }
        compose.onNodeWithTag("paste-pairing-code").performScrollTo().performClick()
        compose.onNodeWithTag("pairing-payload").performTextInput(payload)
        compose.onNodeWithTag("review-pairing").performClick()
        compose.onNodeWithText("Connect to Dust").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, cameraRequests); assertEquals(0, submissions) }
    }

    @Test
    fun cancellingAReviewedInvitationDoesNotPair() {
        var cancelled = 0
        var submissions = 0
        compose.setContent {
            HerdrTheme { PairingScreen(PairingEntryMode.PASTE, false, false, false, null, {}, { submissions++ }, {}, { cancelled++ }) }
        }
        compose.onNodeWithTag("pairing-payload").performTextInput(payload)
        compose.onNodeWithTag("review-pairing").performClick()
        compose.onNodeWithContentDescription("Cancel pairing").performClick()
        compose.runOnIdle { assertEquals(1, cancelled); assertEquals(0, submissions) }
    }

    @Test
    fun screenshotRequiresServerReviewAndConfirmationWithoutCameraPermission() {
        val submitted = mutableListOf<String>()
        var cameraRequests = 0
        compose.setContent {
            HerdrTheme {
                PairingScreen(
                    PairingEntryMode.IMAGE, false, false, false, null,
                    { cameraRequests++ }, { submitted += it }, {}, {},
                    imageChooser = { it(PairingImageResult.Code(payload)) },
                )
            }
        }
        compose.onNodeWithText("Connect to Dust").assertIsDisplayed()
        compose.onNodeWithTag("pairing-host").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, cameraRequests); assertTrue(submitted.isEmpty()) }
        compose.onNodeWithTag("confirm-pairing").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf(payload), submitted); assertEquals(0, cameraRequests) }
    }

    @Test
    fun cancelledAndInvalidScreenshotsKeepPairingUnsentAndAllowAnotherChoice() {
        var cameraRequests = 0
        var submissions = 0
        var selection: ((PairingImageResult) -> Unit)? = null
        compose.setContent {
            HerdrTheme {
                PairingScreen(
                    PairingEntryMode.SCAN, false, true, false, null,
                    { cameraRequests++ }, { submissions++ }, {}, {},
                    imageChooser = { selection = it },
                )
            }
        }
        compose.onNodeWithTag("choose-pairing-image").performScrollTo().performClick()
        compose.onNodeWithTag("choose-pairing-image").assertIsNotEnabled()
        compose.runOnIdle { selection!!(PairingImageResult.Cancelled) }
        compose.onNodeWithTag("choose-pairing-image").assertIsEnabled().performClick()
        compose.runOnIdle { selection!!(PairingImageResult.Code("https://example.com/not-a-pairing-code")) }
        compose.onNodeWithText("This is not a valid Herdr pairing QR code. Generate a new code on your server.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("confirm-pairing").assertDoesNotExist()
        compose.onNodeWithTag("choose-pairing-image").performScrollTo().performClick()
        compose.runOnIdle { selection!!(PairingImageResult.Error("No QR code was found.")) }
        compose.onNodeWithText("No QR code was found.").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, cameraRequests); assertEquals(0, submissions) }
    }

    @Test
    fun expiredScreenshotShowsWarningBeforeConfirmationAndCanBeReplaced() {
        var choices = 0
        var submissions = 0
        compose.setContent {
            HerdrTheme {
                PairingScreen(
                    PairingEntryMode.IMAGE, false, false, false, null,
                    {}, { submissions++ }, {}, {},
                    imageChooser = { callback ->
                        choices++
                        callback(PairingImageResult.Code(if (choices == 1) payload.replace("9223372036854775807", "1") else payload))
                    },
                )
            }
        }
        compose.onNodeWithText("This code may have expired. If pairing fails, request a fresh code on your computer.").assertIsDisplayed()
        compose.onNodeWithText("Use a new code").performScrollTo().performClick()
        compose.onNodeWithText("This code may have expired. If pairing fails, request a fresh code on your computer.").assertDoesNotExist()
        compose.runOnIdle { assertEquals(2, choices); assertEquals(0, submissions) }
    }
}
