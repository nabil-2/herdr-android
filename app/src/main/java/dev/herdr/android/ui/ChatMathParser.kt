package dev.herdr.android.ui

/** Source ranges use Kotlin/Markdown UTF-16 offsets; [end] is exclusive. */
data class ChatMathExpression(
    val start: Int,
    val end: Int,
    val latex: String,
    val display: Boolean,
    val original: String,
)

/**
 * Recognizes explicit math while leaving Markdown code, URLs and HTML untouched.
 * This is a conservative delimiter scanner, not a LaTeX interpreter. Inputs that
 * exceed the work limit remain ordinary Markdown, and renderers must retain the
 * original text if the math library rejects an extracted expression.
 */
fun extractChatMath(source: String): List<ChatMathExpression> {
    if (source.isEmpty() || source.length > MAX_SOURCE_CHARS) return emptyList()
    return MathScanner(source).extract()
}

private const val MAX_SOURCE_CHARS = 1_048_576
private const val MAX_FORMULA_CHARS = 16_384
private const val MAX_FORMULAS = 128
private const val MAX_NESTING = 64
private val BACKTICK = 0x60.toChar()
private val MATH_FENCES = setOf("math", "latex", "tex")
private val URL_PREFIXES = listOf("https://", "http://", "mailto:", "www.")
private val VOID_HTML = setOf("area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr")
private val MATH_COMMANDS = setOf(
    "int", "iint", "iiint", "oint", "sum", "prod", "frac", "dfrac", "tfrac", "sqrt",
    "alpha", "beta", "gamma", "Gamma", "delta", "Delta", "epsilon", "varepsilon",
    "theta", "Theta", "lambda", "Lambda", "mu", "nu", "xi", "pi", "Pi", "rho",
    "sigma", "Sigma", "tau", "phi", "varphi", "Phi", "psi", "Psi", "omega", "Omega",
    "infty", "partial", "nabla", "mathbb", "mathcal", "mathbf", "operatorname",
    "lim", "sup", "inf", "overline", "underbrace", "vec", "cdot", "times", "leq", "geq",
)

private class WorkBudget(size: Int) {
    private var remaining = size * 12L + 1024
    var exhausted = false
        private set

    fun spend(amount: Int = 1): Boolean {
        remaining -= amount
        if (remaining < 0) exhausted = true
        return !exhausted
    }
}

private data class Fence(val marker: Char, val count: Int, val content: Int, val math: Boolean)
private data class HtmlTag(val end: Int, val name: String, val closing: Boolean, val selfClosing: Boolean)
private data class Delimited(val end: Int, val bodyStart: Int, val bodyEnd: Int)

private class MathScanner(private val source: String) {
    private val protected = BooleanArray(source.length)
    private val work = WorkBudget(source.length)
    private val expressions = ArrayList<ChatMathExpression>()

    fun extract(): List<ChatMathExpression> {
        maskBlocks()
        if (work.exhausted) return emptyList()
        maskInline()
        if (work.exhausted) return emptyList()
        var position = 0
        var inlineCount = 0
        while (position < source.length && work.spend() && inlineCount < MAX_FORMULAS) {
            if (protected[position]) {
                position++
                continue
            }
            val candidate: Delimited?
            val display: Boolean
            when {
                source[position] == '\\' -> {
                    val next = source.getOrNull(position + 1)
                    if (next != '(' && next != '[') {
                        position += 2
                        continue
                    }
                    display = next == '['
                    candidate = delimited(position, 2, if (display) "\\]" else "\\)", allowNewline = true)
                }
                source[position] == '$' -> {
                    val count = run(position, '$')
                    if (count !in 1..2) {
                        position += count
                        continue
                    }
                    display = count == 2
                    candidate = delimited(position, count, if (display) "$$" else "$", allowNewline = display)
                }
                source[position] == '[' && paragraphStart(position) -> {
                    display = true
                    candidate = standaloneBrackets(position)
                }
                else -> {
                    position++
                    continue
                }
            }
            if (candidate != null && add(position, candidate, display)) {
                inlineCount++
                position = candidate.end
            } else {
                position += if (source[position] == '\\') 2 else if (source[position] == '$') run(position, '$') else 1
            }
        }
        return expressions.sortedBy { it.start }.take(MAX_FORMULAS)
    }

    private fun mask(start: Int, end: Int) {
        if (end > start) {
            work.spend(end - start)
            java.util.Arrays.fill(protected, start, end, true)
        }
    }

    private fun run(start: Int, marker: Char): Int {
        var end = start
        while (end < source.length && source[end] == marker && work.spend()) end++
        return (end - start).coerceAtLeast(1)
    }

    private fun fence(start: Int, end: Int): Fence? {
        var position = start
        while (position < end && source[position] == ' ' && position - start < 4) position++
        if (position - start > 3 || position >= end || source[position] !in charArrayOf(BACKTICK, '~')) return null
        val marker = source[position]
        val count = run(position, marker)
        if (count < 3) return null
        val suffix = source.substring((position + count).coerceAtMost(end), end).trim()
        // Backticks inside the info string invalidate a CommonMark opening fence.
        if (marker == BACKTICK && BACKTICK in suffix) return null
        return Fence(marker, count, position, suffix.lowercase() in MATH_FENCES)
    }

    private fun maskBlocks() {
        var start = 0
        var opening: Fence? = null
        var fenceStart = 0
        var contentStart = 0
        while (start < source.length && work.spend()) {
            val newline = source.indexOf('\n', start)
            val end = if (newline < 0) source.length else newline
            work.spend(end - start)
            val lineEnd = if (newline < 0) end else end + 1
            val marker = fence(start, end)
            val active = opening
            if (active != null) {
                mask(start, lineEnd)
                if (marker != null && marker.marker == active.marker && marker.count >= active.count &&
                    source.substring(marker.content + marker.count, end).isBlank()) {
                    if (active.math && expressions.size < MAX_FORMULAS) {
                        val formula = Delimited(end, contentStart, start)
                        add(fenceStart, formula, display = true, checkProtected = false)
                    }
                    opening = null
                }
            } else if (marker != null) {
                opening = marker
                fenceStart = start
                contentStart = lineEnd
                mask(start, lineEnd)
            } else {
                var indent = start
                while (indent < end && source[indent] == ' ' && work.spend()) indent++
                if (indent - start >= 4 || source.getOrNull(indent) == '\t' || referenceDefinition(indent, end)) {
                    mask(start, lineEnd)
                }
            }
            start = lineEnd
        }
    }

    private fun referenceDefinition(start: Int, end: Int): Boolean {
        if (source.getOrNull(start) != '[') return false
        var position = start + 1
        while (position < end && work.spend()) {
            when (source[position]) {
                '\\' -> position += 2
                '[' -> return false
                ']' -> return source.getOrNull(position + 1) == ':'
                else -> position++
            }
        }
        return false
    }

    private fun maskInline() {
        var position = 0
        while (position < source.length && work.spend()) {
            if (protected[position]) {
                position++
                continue
            }
            when {
                source[position] == '\\' -> position += 2
                source[position] == BACKTICK -> {
                    val count = run(position, BACKTICK)
                    var closing = position + count
                    var found = false
                    while (closing < source.length && work.spend()) {
                        if (source[closing] == BACKTICK && !protected[closing]) {
                            val closingCount = run(closing, BACKTICK)
                            if (closingCount == count) {
                                closing += count
                                found = true
                                break
                            }
                            closing += closingCount
                        } else closing++
                    }
                    if (found) {
                        mask(position, closing)
                        position = closing
                    } else position += count
                }
                source[position] == '<' -> {
                    val htmlEnd = htmlRange(position)
                    if (htmlEnd != null) {
                        mask(position, htmlEnd)
                        position = htmlEnd
                    } else position++
                }
                source[position] == ']' -> {
                    var next = position + 1
                    while (next < source.length && source[next] in " \t" && work.spend()) next++
                    if (source.getOrNull(next) == '(') {
                        val end = linkDestination(next)
                        mask(next, end)
                        position = end
                    } else if (source.getOrNull(next) == '[') {
                        val end = referenceLabel(next)
                        mask(next, end)
                        position = end
                    } else position++
                }
                urlStarts(position) -> {
                    var end = position
                    while (end < source.length && !source[end].isWhitespace() && source[end] !in "<>\"'" && work.spend()) end++
                    mask(position, end)
                    position = end
                }
                else -> position++
            }
        }
    }

    private fun urlStarts(position: Int): Boolean =
        (position == 0 || !source[position - 1].isLetterOrDigit()) &&
            URL_PREFIXES.any { source.regionMatches(position, it, 0, it.length, ignoreCase = true) }

    private fun linkDestination(start: Int): Int {
        var position = start + 1
        var nesting = 1
        var quote: Char? = null
        while (position < source.length && work.spend()) {
            if (source[position] == '\\') {
                position += 2
                continue
            }
            if (quote != null) {
                if (source[position] == quote) quote = null
                position++
                continue
            }
            when (source[position]) {
                '\'', '"' -> {
                    quote = source[position]
                    position++
                }
                '(' -> {
                    nesting++
                    if (nesting > MAX_NESTING) return paragraphEnd(position)
                    position++
                }
                ')' -> {
                    nesting--
                    if (nesting == 0) return position + 1
                    position++
                }
                else -> position++
            }
        }
        // A malformed destination is still not a source of executable math.
        return source.length
    }

    private fun referenceLabel(start: Int): Int {
        var position = start + 1
        while (position < source.length && work.spend()) {
            if (source[position] == '\\') position += 2
            else if (source[position] == ']') return position + 1
            else if (source[position] == '\n') return position
            else position++
        }
        return source.length
    }

    private fun htmlRange(start: Int): Int? {
        if (source.startsWith("<!--", start)) {
            val end = source.indexOf("-->", start + 4)
            work.spend(if (end < 0) source.length - start else end + 3 - start)
            return if (end < 0) source.length else end + 3
        }
        if (source.startsWith("<!", start) || source.startsWith("<?", start) ||
            source.startsWith("<https://", start, ignoreCase = true) || source.startsWith("<http://", start, ignoreCase = true)) {
            val end = source.indexOf('>', start + 1)
            work.spend(if (end < 0) source.length - start else end + 1 - start)
            return if (end < 0) paragraphEnd(start) else end + 1
        }
        val first = htmlTag(start) ?: return null
        if (first.closing || first.selfClosing || first.name in VOID_HTML) return first.end
        var position = first.end
        var nesting = 1
        while (position < source.length && work.spend()) {
            if (source[position] == '<') {
                val tag = htmlTag(position)
                if (tag != null) {
                    if (tag.name == first.name) {
                        if (tag.closing) nesting-- else if (!tag.selfClosing) nesting++
                        if (nesting == 0) return tag.end
                        if (nesting > MAX_NESTING) return source.length
                    }
                    position = tag.end
                    continue
                }
            }
            position++
        }
        return paragraphEnd(start)
    }

    private fun htmlTag(start: Int): HtmlTag? {
        var position = start + 1
        val closing = source.getOrNull(position) == '/'
        if (closing) position++
        val nameStart = position
        if (source.getOrNull(position)?.isLetter() != true) return null
        while (position < source.length && (source[position].isLetterOrDigit() || source[position] in "-:") && work.spend()) position++
        val name = source.substring(nameStart, position).lowercase()
        if (source.getOrNull(position)?.let { !it.isWhitespace() && it != '>' && it != '/' } == true) return null
        var quote: Char? = null
        while (position < source.length && work.spend()) {
            val character = source[position]
            if (quote != null) {
                if (character == quote) quote = null
            } else if (character == '\'' || character == '"') quote = character
            else if (character == '>') return HtmlTag(position + 1, name, closing, source.getOrNull(position - 1) == '/')
            position++
        }
        return null
    }

    private fun delimited(start: Int, openerSize: Int, closer: String, allowNewline: Boolean): Delimited? {
        val bodyStart = start + openerSize
        if (bodyStart >= source.length || (openerSize == 1 && source[bodyStart].isWhitespace())) return null
        val limit = minOf(source.length, bodyStart + MAX_FORMULA_CHARS + closer.length)
        var position = bodyStart
        while (position < limit && work.spend()) {
            if (protected[position] || (!allowNewline && source[position] in "\r\n")) return null
            if (source.startsWith(closer, position)) {
                if (closer[0] == '$' && (source.getOrNull(position - 1) == '$' || source.getOrNull(position + closer.length) == '$')) return null
                if (openerSize == 1 && (source[position - 1].isWhitespace() || source.getOrNull(position + 1)?.isDigit() == true)) return null
                return Delimited(position + closer.length, bodyStart, position)
            }
            if (source[position] == '\\') position += 2 else position++
        }
        return null
    }

    private fun paragraphStart(position: Int): Boolean {
        var previous = position - 1
        var indent = 0
        while (previous >= 0 && source[previous] != '\n' && work.spend()) {
            if (source[previous] != ' ' || ++indent > 3) return false
            previous--
        }
        if (previous < 0) return true
        previous--
        while (previous >= 0 && source[previous] != '\n' && work.spend()) {
            if (!source[previous].isWhitespace()) return false
            previous--
        }
        return !work.exhausted
    }

    private fun paragraphEnd(start: Int): Int {
        var position = start
        while (position < source.length && work.spend()) {
            if (source[position] == '\n') {
                var next = position + 1
                while (next < source.length && source[next] in " \t\r" && work.spend()) next++
                if (next == source.length || source[next] == '\n') return position
            }
            position++
        }
        return source.length
    }

    private fun standaloneBrackets(start: Int): Delimited? {
        var position = start + 1
        var nesting = 1
        val limit = minOf(source.length, start + MAX_FORMULA_CHARS + 2)
        while (position < limit && work.spend()) {
            if (protected[position]) return null
            when (source[position]) {
                '\\' -> position += 2
                '[' -> {
                    if (++nesting > MAX_NESTING) return null
                    position++
                }
                ']' -> {
                    if (--nesting == 0) {
                        val end = position + 1
                        var next = end
                        while (next < source.length && source[next] in " \t\r" && work.spend()) next++
                        if (next < source.length && source[next] != '\n') return null
                        if (next < source.length) {
                            next++
                            while (next < source.length && source[next] in " \t\r" && work.spend()) next++
                            if (next < source.length && source[next] != '\n') return null
                        }
                        val body = source.substring(start + 1, position)
                        return if (unmistakableMath(body)) Delimited(end, start + 1, position) else null
                    }
                    position++
                }
                else -> position++
            }
        }
        return null
    }

    private fun unmistakableMath(body: String): Boolean {
        var position = 0
        while (position < body.length && work.spend()) {
            if (body[position] == '\\') {
                val commandStart = ++position
                while (position < body.length && body[position].isLetter() && work.spend()) position++
                if (body.substring(commandStart, position) in MATH_COMMANDS) return true
                if (position == commandStart) position++
            } else position++
        }
        return false
    }

    private fun add(start: Int, candidate: Delimited, display: Boolean, checkProtected: Boolean = true): Boolean {
        if (candidate.bodyEnd <= candidate.bodyStart || candidate.bodyEnd - candidate.bodyStart > MAX_FORMULA_CHARS) return false
        if (checkProtected) {
            for (position in start until candidate.end) {
                if (!work.spend() || protected[position]) return false
            }
        }
        val latex = source.substring(candidate.bodyStart, candidate.bodyEnd).trim()
        // Explicit, closed delimiters must survive CommonMark even when the
        // formula itself is malformed. The renderer validates it and falls
        // back to original text. Plain bracket inference stays conservative.
        if (latex.isEmpty() || (source[start] == '[' && !balancedBraces(latex))) return false
        // A bare numeric dollar amount is ambiguous and remains normal text.
        if (!display && source[start] == '$' && latex.all { it.isDigit() || it in "., \t" }) return false
        expressions += ChatMathExpression(start, candidate.end, latex, display, source.substring(start, candidate.end))
        return true
    }

    private fun balancedBraces(body: String): Boolean {
        var nesting = 0
        var position = 0
        while (position < body.length && work.spend()) {
            when (body[position]) {
                '\\' -> position += 2
                '{' -> {
                    if (++nesting > MAX_NESTING) return false
                    position++
                }
                '}' -> {
                    if (--nesting < 0) return false
                    position++
                }
                else -> position++
            }
        }
        return !work.exhausted && nesting == 0
    }
}
