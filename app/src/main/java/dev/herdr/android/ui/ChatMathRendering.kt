package dev.herdr.android.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.unit.sp
import com.hrm.latex.parser.LatexParser
import com.hrm.latex.renderer.Latex
import com.hrm.latex.renderer.measure.rememberLatexMeasurer
import com.hrm.latex.renderer.model.LatexConfig
import com.hrm.latex.renderer.model.LatexTheme
import com.hrm.latex.renderer.model.LineBreakingConfig
import com.mikepenz.markdown.model.MarkdownAnnotator
import com.mikepenz.markdown.model.MarkdownInlineContent
import com.mikepenz.markdown.model.markdownAnnotator
import com.mikepenz.markdown.model.markdownInlineContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.flavours.gfm.GFMElementTypes

internal data class MathMarkdown(
    val source: String,
    val expressions: Map<String, ChatMathExpression>,
)

/** Protect backslashes and subscripts before CommonMark can consume them. */
internal fun prepareMathMarkdown(source: String): MathMarkdown {
    val expressions = extractChatMath(source)
    if (expressions.isEmpty()) return MathMarkdown(source, emptyMap())
    var prefix = "\uE000HERDRMATH"
    while (source.contains(prefix)) prefix += "\uE002"
    val replacements = linkedMapOf<String, ChatMathExpression>()
    var offset = 0
    val protected = buildString {
        expressions.forEachIndexed { index, expression ->
            append(source, offset, expression.start)
            val token = "$prefix${index}END\uE001"
            replacements[token] = expression
            append(token)
            offset = expression.end
        }
        append(source, offset, source.length)
    }
    return MathMarkdown(protected, replacements)
}

/** Bound recursive parser/layout work; unsupported formulae stay readable verbatim. */
internal fun canRenderChatMath(latex: String): Boolean {
    if (latex.isBlank() || latex.length > 4_096 || latex.any { it.code < 32 && it !in "\n\r\t" }) return false
    var depth = 0
    var commands = 0
    var index = 0
    while (index < latex.length) {
        when (latex[index]) {
            '\\' -> {
                index++
                val start = index
                while (index < latex.length && latex[index].isLetter()) index++
                if (index > start) {
                    if (++commands > 256) return false
                    val command = latex.substring(start, index)
                    if (command in forbiddenMathCommands) return false
                } else index++
                continue
            }
            '{' -> if (++depth > 16) return false
            '}' -> if (--depth < 0) return false
        }
        index++
    }
    return depth == 0
}

private val forbiddenMathCommands = setOf(
    "def", "gdef", "edef", "xdef", "let", "futurelet", "newcommand", "renewcommand",
    "providecommand", "DeclareMathOperator", "newenvironment", "renewenvironment", "global",
    "csname", "endcsname", "expandafter", "noexpand", "input", "include", "write", "read",
    "openin", "openout", "catcode", "loop", "repeat", "href", "url",
)

internal data class MathMarkdownRendering(val annotator: MarkdownAnnotator, val inline: MarkdownInlineContent)

@Composable
internal fun rememberMathMarkdownRendering(prepared: MathMarkdown, widthPx: Float): MathMarkdownRendering {
    val density = LocalDensity.current
    val color = MaterialTheme.colorScheme.onSurface
    val config = remember(color) { LatexConfig(fontSize = 17.sp, theme = LatexTheme.light(color), accessibilityEnabled = true) }
    val measurer = rememberLatexMeasurer(config)
    var valid by remember(prepared) { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(prepared) {
        valid = withContext(Dispatchers.Default) {
            prepared.expressions.filterValues { expression ->
                canRenderChatMath(expression.latex) && runCatching {
                    val result = LatexParser().parseWithDiagnostics(expression.latex)
                    result.document.children.isNotEmpty() && !result.hasDiagnostics
                }.getOrDefault(false)
            }.keys
        }
    }
    val inline = remember(prepared, valid, config, measurer, density, widthPx) {
        prepared.expressions.entries.mapIndexedNotNull { index, (token, expression) ->
            if (token !in valid || widthPx <= 0f) return@mapIndexedNotNull null
            val wrapping = config.copy(lineBreaking = LineBreakingConfig(enabled = true, maxWidth = widthPx))
            val natural = measurer.measure(expression.latex, config) ?: return@mapIndexedNotNull null
            val block = expression.display || natural.widthPx > widthPx
            val renderConfig = if (block) wrapping else config
            val measured = if (block) measurer.measure(expression.latex, wrapping) else natural
            if (measured == null || !measured.heightPx.isFinite() || !measured.widthPx.isFinite() ||
                measured.heightPx <= 0 || measured.heightPx > 4_096 * density.density) return@mapIndexedNotNull null
            token to InlineTextContent(
                Placeholder(
                    width = with(density) { (if (block) widthPx else measured.widthPx).toSp() },
                    height = with(density) { measured.heightPx.toSp() },
                    placeholderVerticalAlign = PlaceholderVerticalAlign.TextCenter,
                ),
            ) {
                Box(Modifier.fillMaxSize().testTag("chat-math-$index").semantics {
                    contentDescription = "Math: ${expression.latex}"
                }.horizontalScroll(rememberScrollState())) {
                    Latex(expression.latex, config = renderConfig)
                }
            }
        }.toMap()
    }
    val annotator = remember(prepared, inline) {
        markdownAnnotator { content, child ->
            // GFM also recognizes dollar-delimited text (including currency).
            // All accepted formulas were protected before parsing. Preserve
            // the remaining math nodes that the default renderer would omit.
            if (child.type == GFMElementTypes.INLINE_MATH || child.type == GFMElementTypes.BLOCK_MATH) {
                append(content.substring(child.startOffset, child.endOffset))
                true
            } else if (child.type != MarkdownTokenTypes.TEXT) false else {
                val text = content.substring(child.startOffset, child.endOffset)
                var cursor = 0
                var consumed = false
                while (cursor < text.length) {
                    val next = prepared.expressions.keys.mapNotNull { token ->
                        text.indexOf(token, cursor).takeIf { it >= 0 }?.let { it to token }
                    }.minByOrNull { it.first } ?: break
                    append(text.substring(cursor, next.first))
                    val expression = prepared.expressions.getValue(next.second)
                    // A placeholder cannot span paragraph breaks. Fenced and
                    // display source can contain newlines; the native child
                    // supplies its accessible formula, and Copy uses source.
                    if (next.second in inline) appendInlineContent(next.second, "\uFFFC")
                    else append(expression.original)
                    cursor = next.first + next.second.length
                    consumed = true
                }
                if (consumed) append(text.substring(cursor))
                consumed
            }
        }
    }
    return MathMarkdownRendering(annotator, markdownInlineContent(inline))
}
