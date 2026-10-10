package dev.herdr.android.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.herdr.android.data.ChatContent
import dev.herdr.android.data.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Native rendering fixtures contain no real conversations or server credentials. */
@RunWith(AndroidJUnit4::class)
class NativeMathUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var fixtureName = "math"

    @Test
    fun inlineIntegralAndFractionAreDrawnAsNativeMath() {
        val latex = "\\int_0^1 x^2\\,dx=\\frac{1}{3}"
        render(ChatMessage("inline", "assistant", "Integrate \\($latex\\) exactly."))
        awaitMath(0)
        compose.onNodeWithContentDescription("Math: $latex", useUnmergedTree = true).assertIsDisplayed()
        assertDrawn(0)
        saveScreenshot("math-integral-device.png")
    }

    @Test
    fun displayIntegralAndAlignedDerivativesRenderAtNarrowWidthAndLargeFont() {
        val latex = "\\begin{aligned}f(x)&=x^2\\\\ f'(x)&=2x\\\\ F(x)&=\\frac{x^3}{3}+C\\end{aligned}"
        render(ChatMessage("aligned", "assistant", "$$$latex$$"), narrow = true)
        awaitMath(0)
        val root = compose.onNodeWithTag("math-test-root").fetchSemanticsNode().boundsInRoot
        val math = compose.onNodeWithTag("chat-math-0", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("Math must fit the available message width", math.left >= root.left && math.right <= root.right + 1)
        assertTrue("Aligned rows should have visible height", math.height > 40)
        assertDrawn(0)
        saveScreenshot("math-aligned-narrow-device.png")
    }

    @Test
    fun displayIntegralWithRadicalAndSuperscriptIsDrawn() {
        val latex = "\\int_0^\\infty e^{-x^2}\\,dx=\\frac{\\sqrt{\\pi}}{2}"
        render(ChatMessage("display", "assistant", "\\[$latex\\]"))
        awaitMath(0)
        assertDrawn(0)
    }

    @Test
    fun displayFormulaWithRealLineBreaksDrawsItsIntegralAndFraction() {
        val original = """
            \[
            \int_0^1 x^2\,dx
            = \frac{1}{3}
            \]
        """.trimIndent()
        render(ChatMessage("multiline-display", "assistant", original))
        awaitMath(0)
        assertDrawn(0)
    }

    @Test
    fun oversizedFormulaRemainsReadableWithoutInvokingTheRenderer() {
        val original = "$$\n${"x".repeat(16_385)}\n$$"
        render(ChatMessage("oversized", "assistant", original))
        awaitText(original)
        compose.onNodeWithTag("chat-math-0", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun malformedOrForbiddenFormulaRemainsReadableLiteral() {
        val original = "\\[\\frac{1}{2\\] and \\(\\input{secret}\\)"
        render(ChatMessage("fallback", "assistant", original))
        compose.waitForIdle()
        diagnostics("math-fallback")
        compose.onNodeWithTag("chat-math-0", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("chat-math-1", useUnmergedTree = true).assertDoesNotExist()
        awaitText(original)
    }

    @Test
    fun currencyBracketsAndCodeAreNeverReinterpretedAsMath() {
        val original = "Cost $5 and $10. [ordinary brackets]\n\n`\\(x_1^2\\)`\n\n```kotlin\n\\[\\frac{1}{3}\\]\n```"
        render(ChatMessage("literals", "assistant", original))
        compose.waitForIdle()
        diagnostics("math-literals")
        compose.onNodeWithTag("chat-math-0", useUnmergedTree = true).assertDoesNotExist()
        awaitText("Cost $5 and $10. [ordinary brackets]")
        awaitText("\\[\\frac{1}{3}\\]")
    }

    @Test
    fun mathematicalFencesAndPlainIntegralBracketsRender() {
        render(ChatMessage("math-fence", "assistant", "```math\n\\frac{a_1+b^2}{c_3}\n```\n\n[\\int_0^1 x^2\\,dx=\\frac{1}{3}]"))
        awaitMath(0)
        awaitMath(1)
        assertDrawn(0)
        assertDrawn(1)
    }

    @Test
    fun copyingAMathAnswerPreservesTheCompleteOriginalMarkdownAndLatex() {
        val original = """
            # An integral

            For \(x_1^2\), integrate term by term:

            \[
            \int_0^1 x^2\,dx = \frac{1}{3}.
            \]

            ```latex
            \frac{a_1+b^2}{c_3}
            ```
        """.trimIndent()
        val clipboard = compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previous = clipboard.primaryClip
        try {
            render(ChatMessage("copy-math", "assistant", original))
            compose.waitUntil(5_000) { compose.activity.hasWindowFocus() }
            compose.onNodeWithContentDescription("Copy Codex message").performScrollTo().assertIsDisplayed().performClick()
            compose.waitUntil(5_000) { clipboard.primaryClip?.getItemAt(0)?.text?.toString() == original }
            compose.runOnIdle { assertEquals(original, clipboard.primaryClip?.getItemAt(0)?.text?.toString()) }
        } finally {
            compose.runOnIdle {
                if (previous != null) clipboard.setPrimaryClip(previous)
                else clipboard.clearPrimaryClip()
            }
        }
    }

    @Test
    fun userInputMathAndMarkdownRemainLiteral() {
        val literal = "**Review** \\(\\int_0^1 x^2\\,dx\\) and \\[\\frac{1}{3}\\]."
        render(ChatMessage("literal-user-math", "user", literal))
        compose.onNodeWithText(literal).assertIsDisplayed()
    }

    private fun awaitMath(index: Int) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("chat-math-$index", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("chat-math-$index", useUnmergedTree = true).performScrollTo()
        diagnostics("math-expression-$index")
        compose.onNodeWithTag("chat-math-$index", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun assertDrawn(index: Int) {
        val pixels = compose.onNodeWithTag("chat-math-$index", useUnmergedTree = true).captureToImage().toPixelMap()
        val colors = mutableSetOf<Int>()
        for (y in 0 until pixels.height) for (x in 0 until pixels.width) colors += pixels[x, y].hashCode()
        assertTrue("A semantic box alone is not a rendered formula", colors.size > 8)
    }

    private fun saveScreenshot(name: String) {
        val bitmap = compose.onNodeWithTag("math-test-root").captureToImage().asAndroidBitmap()
        File(compose.activity.cacheDir, name).outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun diagnostics(name: String) {
        File(compose.activity.cacheDir, "$fixtureName-$name-semantics.txt").writeText(compose.onRoot(useUnmergedTree = true).printToString())
        saveScreenshot("$fixtureName-$name-diagnostic.png")
    }

    private fun awaitText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(text, substring = true).performScrollTo().assertIsDisplayed()
    }

    private fun render(message: ChatMessage, narrow: Boolean = false) {
        fixtureName = message.id
        compose.setContent {
            HerdrTheme {
                val density = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(density.density, if (narrow) 1.8f else density.fontScale)) {
                    Box((if (narrow) Modifier.width(260.dp) else Modifier.fillMaxSize()).background(MaterialTheme.colorScheme.background).testTag("math-test-root")) {
                        ConversationMessages(ChatContent(bindingId = "synthetic-math", messages = listOf(message)), {}, Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}
