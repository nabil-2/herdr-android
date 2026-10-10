package dev.herdr.android.ui

import org.junit.Assert.*
import org.junit.Test

class ChatMathRenderingTest {
    @Test fun protectsMathFromMarkdownWithoutChangingSurroundingStructure() {
        val source = "- **Integral** \\(x_1^2\\)\n\n> \\[\\frac{a}{b}\\]"
        val prepared = prepareMathMarkdown(source)
        assertEquals(2, prepared.expressions.size)
        var restored = prepared.source
        prepared.expressions.forEach { (token, expression) -> restored = restored.replace(token, expression.original) }
        assertEquals(source, restored)
        assertTrue(prepared.source.startsWith("- **Integral** "))
        assertTrue(prepared.source.contains("\n\n> "))
        assertFalse(prepared.source.contains("x_1"))
    }

    @Test fun sourceCannotCollideWithGeneratedMathTokens() {
        val original = "\uE000HERDRMATH0END\uE001 plus \\(x^2\\)"
        val prepared = prepareMathMarkdown(original)
        assertTrue(prepared.source.startsWith("\uE000HERDRMATH0END\uE001 plus "))
        assertEquals(original, prepared.expressions.entries.fold(prepared.source) { text, entry -> text.replace(entry.key, entry.value.original) })
    }

    @Test fun ordinaryCodeAndCurrencyDoNotNeedRenderer() {
        val source = "Cost $5 or $10. `\\(code\\)`\n\n```kotlin\nval x = \"$\"\n```"
        assertEquals(MathMarkdown(source, emptyMap()), prepareMathMarkdown(source))
    }

    @Test fun renderWorkIsBoundedAndFormulaMacrosCannotExpand() {
        assertTrue(canRenderChatMath("\\int_0^1 x^2\\,dx=\\frac{1}{3}"))
        assertFalse(canRenderChatMath("a".repeat(4_097)))
        assertFalse(canRenderChatMath("{".repeat(17) + "x" + "}".repeat(17)))
        assertFalse(canRenderChatMath("\\def\\x{\\x}\\x"))
        assertFalse(canRenderChatMath("\\newcommand{\\x}{x}\\x"))
        assertFalse(canRenderChatMath("\\input{secret}"))
        assertFalse(canRenderChatMath("\\href{https://example.org}{x}"))
        assertFalse(canRenderChatMath("\\alpha ".repeat(257)))
        assertFalse(canRenderChatMath("\\frac{a}{b"))
        assertFalse(canRenderChatMath("x}"))
        assertFalse(canRenderChatMath("x\u0000"))
    }

    @Test fun escapedBracesDoNotCountAsNesting() {
        assertTrue(canRenderChatMath("\\left\\{x_1\\right\\}"))
    }
}
