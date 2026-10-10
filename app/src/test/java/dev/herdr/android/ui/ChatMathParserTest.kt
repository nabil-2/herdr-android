package dev.herdr.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMathParserTest {
    // Visible substitutes keep Kotlin string interpolation out of Markdown cases.
    private fun markdown(value: String): String = value.replace('§', '$').replace('´', 0x60.toChar())
    private fun parse(value: String): List<ChatMathExpression> = extractChatMath(markdown(value))

    @Test fun explicitInlineAndDisplayDelimitersPreserveSourceOffsets() {
        val source = markdown("""Before \(x^2\), §y_1§ and \[\int_0^1 f(x)\,dx\], then §§\psi(x)§§.""")
        val results = extractChatMath(source)
        assertEquals(listOf("x^2", "y_1", """\int_0^1 f(x)\,dx""", """\psi(x)"""), results.map { it.latex })
        assertEquals(listOf(false, false, true, true), results.map { it.display })
        assertEquals(listOf("""\(x^2\)""", markdown("§y_1§"), """\[\int_0^1 f(x)\,dx\]""", markdown("""§§\psi(x)§§""")), results.map { it.original })
        results.forEach { assertEquals(it.original, source.substring(it.start, it.end)) }
        assertTrue(results.zipWithNext().all { (before, after) -> before.end <= after.start })
    }

    @Test fun offsetsIncludeUtf16SurrogatePairsAndCrLfWithoutRewritingText() {
        val source = "😀 雪\r\n" + """\(\alpha\)"""
        val result = extractChatMath(source).single()
        assertEquals(6, result.start)
        assertEquals(source.length, result.end)
        assertEquals("""\(\alpha\)""", result.original)
        assertEquals("""\alpha""", result.latex)
    }

    @Test fun mathFencesAreDisplayExpressionsAndContainLiteralDelimiterCharacters() {
        for (language in listOf("math", "latex", "tex", "LaTeX")) {
            val source = markdown("Intro\n\n´´´$language\n" + """\frac{x}{2} + \psi(x)""" + "\n´´´\n\nAfter")
            val result = extractChatMath(source).single()
            assertTrue(result.display)
            assertEquals("""\frac{x}{2} + \psi(x)""", result.latex)
            assertEquals(source.substring(result.start, result.end), result.original)
            assertTrue(result.original.startsWith(markdown("´´´$language")))
            assertTrue(result.original.endsWith(markdown("´´´")))
        }
    }

    @Test fun longerAndTildeMathFencesUseMatchingClosingMarker() {
        val source = markdown("  ´´´´math\r\nx + y\r\n´´´\r\n  ´´´´´\r\n\n~~~tex\nz\n~~~")
        val results = extractChatMath(source)
        assertEquals(2, results.size)
        assertEquals(markdown("x + y\r\n´´´"), results[0].latex)
        assertEquals("z", results[1].latex)
        assertEquals(0, results[0].start)
    }

    @Test fun ordinaryAndUnclosedFencesAreNeverParsedAsMath() {
        val source = markdown("´´´kotlin\n§x§ and " + """\(\psi\)""" + "\n´´´\n\n~~~\n§y§\n~~~\n\n´´´math\n§z§")
        assertTrue(extractChatMath(source).isEmpty())
    }

    @Test fun inlineCodeWithVariableBacktickRunsSuppressesMath() {
        val source = markdown("´§x§´ and ´´literal ´ §y§ ´´; outside §z§")
        assertEquals(listOf("z"), extractChatMath(source).map { it.latex })
    }

    @Test fun multilineInlineCodeAndIndentedCodeStayLiteral() {
        val source = markdown("´code\n§x§´\n\n    §y§\n\t" + """\(\psi\)""" + "\n\n§z§")
        assertEquals(listOf("z"), extractChatMath(source).map { it.latex })
    }

    @Test fun urlsLinkDestinationsAndReferenceDefinitionsStayLiteral() {
        val source = markdown("[link](https://example.test/§x§ \"title §t§\")\n" +
            "[relative](docs/(§y§)) and https://example.test/§z§ www.example.test/§w§\n" +
            "[reference]: https://example.test/§r§ \"§s§\"\n\nOutside §q§")
        assertEquals(listOf("q"), extractChatMath(source).map { it.latex })
    }

    @Test fun linkLabelsCanContainMathWithoutChangingTheirDestination() {
        val source = markdown("[A §x§ label](https://example.test/§y§)")
        val expression = extractChatMath(source).single()
        assertEquals("x", expression.latex)
        assertEquals(markdown("§x§"), source.substring(expression.start, expression.end))
    }

    @Test fun quotedLinkTitlesAndReferenceIdsStayOpaque() {
        val source = markdown("""[link](docs/a "title ) §x§") and [label][reference-§y§] with §z§""")
        assertEquals(listOf("z"), extractChatMath(source).map { it.latex })
    }

    @Test fun htmlAttributesElementsCommentsAndAutolinksAreOpaque() {
        val source = markdown("""<span title="§a§">§b§<span>§c§</span></span> """ +
            "<!-- §d§\n§e§ --> <https://example.test/§f§> <img alt=\"§g§\"/>\n\n§h§")
        assertEquals(listOf("h"), extractChatMath(source).map { it.latex })
    }

    @Test fun htmlBlockContentAndQuotedGreaterThanCannotLeakMath() {
        val source = markdown("<div title=\"> §a§\">\n§b§\n</div>\n\n§c§")
        assertEquals(listOf("c"), extractChatMath(source).map { it.latex })
    }

    @Test fun currencyAndEscapedDollarsRemainText() {
        val source = markdown("""Costs §5, §10, §5,§10, §1,234.50§ and \§x\§. Escaped \\(x\\). Real §2 + 2 = 4§.""")
        assertEquals(listOf("2 + 2 = 4"), extractChatMath(source).map { it.latex })
    }

    @Test fun singleDollarRequiresTightSameLineBoundaries() {
        assertTrue(parse("§ x§; §x §; §a\nb§; §x§10; §§§x§§§").isEmpty())
        assertEquals(listOf("x", "y"), parse("§x§\n§y§").map { it.latex })
    }

    @Test fun displayAndBackslashMathCanSpanLines() {
        val source = markdown("§§\n" + """\frac{x}{y}""" + "\n§§\n" + """\(a +
b\)""" + "\n" + """\[
c + d
\]""")
        assertEquals(listOf("""\frac{x}{y}""", "a +\nb", "c + d"), extractChatMath(source).map { it.latex })
    }

    @Test fun unclosedBlankAndMalformedExpressionsRemainLiteral() {
        for (source in listOf("""\(x""", """\[x""", "§x", "§§x", """\(\)""", "§§   §§",
            """\[\{x]""")) {
            assertTrue(source, parse(source).isEmpty())
        }
    }

    @Test fun closedMalformedLatexIsProtectedForVerbatimRendererFallback() {
        for (source in listOf("""\(\frac{x}{y\)""", """\({x}}\)""")) {
            assertEquals(source, parse(source).single().original)
        }
    }

    @Test fun escapedBracesAreNotConsideredUnbalancedGroups() {
        assertEquals(listOf("""\{x\} + \frac{a}{b}"""), parse("""\(\{x\} + \frac{a}{b}\)""").map { it.latex })
    }

    @Test fun standaloneBracketParagraphRequiresAnUnmistakableMathCommand() {
        val source = """Prose.

[
\int_{-\infty}^{\infty} \psi(x)\,dx
]

After."""
        val expression = extractChatMath(source).single()
        assertTrue(expression.display)
        assertEquals("""\int_{-\infty}^{\infty} \psi(x)\,dx""", expression.latex)
        assertEquals("[\n" + expression.latex + "\n]", expression.original)
    }

    @Test fun ordinaryBracketsInlineBracketsAndMarkdownLinksAreNotDisplayMath() {
        for (source in listOf("[normal prose]", "[x + y]", """[\text{ordinary prose}]""",
            """Text [\int x]""", """[\psi](https://example.test)""", """[\int x]
continuation""", """[\\psi]""")) {
            assertTrue(source, extractChatMath(source).isEmpty())
        }
    }

    @Test fun standaloneBracketsHonorBalancedNestedSquareBrackets() {
        assertEquals("""\int [f(x)]\,dx""", extractChatMath("""[ \int [f(x)]\,dx ]""").single().latex)
        assertTrue(extractChatMath("""[ \int [f(x)\,dx ]""").isEmpty())
        assertTrue(extractChatMath("""[ \intensity ]""").isEmpty())
    }

    @Test fun escapedOpenersAndCodeInsideBracketsRemainLiteral() {
        val source = markdown("""\\(x\) and \§x§

[ \int ´x´ ]

Real \(y\)""")
        assertEquals(listOf("y"), extractChatMath(source).map { it.latex })
    }

    @Test fun formulaCountAndSizesAreBoundedWhileEarlierValidRangesAreRetained() {
        val source = List(200) { markdown("§x§") }.joinToString(" ")
        val expressions = extractChatMath(source)
        assertEquals(128, expressions.size)
        assertTrue(expressions.zipWithNext().all { (a, b) -> a.end <= b.start })
        assertTrue(parse("§§" + "x".repeat(16_385) + "§§").isEmpty())
        assertTrue(parse("´´´math\n" + "x".repeat(16_385) + "\n´´´").isEmpty())
        assertTrue(extractChatMath("x".repeat(1_048_577)).isEmpty())
    }

    @Test fun nestingDepthIsBounded() {
        val valid = """[ \psi """ + "{".repeat(64) + "x" + "}".repeat(64) + " ]"
        assertEquals(1, extractChatMath(valid).size)
        val excessive = """[ \psi """ + "{".repeat(65) + "x" + "}".repeat(65) + " ]"
        assertTrue(extractChatMath(excessive).isEmpty())
        val explicit = """\(""" + "{".repeat(65) + "x" + "}".repeat(65) + """\)"""
        val protected = extractChatMath(explicit).single()
        assertEquals(explicit, protected.original)
        assertFalse(canRenderChatMath(protected.latex))
    }

    @Test(timeout = 2000) fun adversarialOpenersAndUnclosedCodeHaveBoundedScanWork() {
        assertTrue(extractChatMath("[".repeat(200_000)).isEmpty())
        assertTrue(extractChatMath("<span ".repeat(20_000)).isEmpty())
        assertTrue(parse("´".repeat(100_000)).isEmpty())
        assertFalse(parse("§x§").isEmpty())
    }

    @Test fun returnedSourceRangesAlwaysRoundTripAndDoNotOverlapAcrossKinds() {
        val source = markdown("§x§\n\n´´´math\ny\n´´´\n\n" + """[ \psi ]""" + "\n\n" + """\(z\)""")
        val expressions = extractChatMath(source)
        assertEquals(listOf("x", "y", """\psi""", "z"), expressions.map { it.latex })
        expressions.forEach { assertEquals(it.original, source.substring(it.start, it.end)) }
        assertTrue(expressions.zipWithNext().all { (a, b) -> a.end <= b.start })
    }
}
