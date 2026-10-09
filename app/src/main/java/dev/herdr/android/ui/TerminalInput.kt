package dev.herdr.android.ui

/** Only local key/IME actions use this encoder. Remote output is never executable input. */
internal object TerminalInputEncoder {
    enum class Key {
        ENTER, TAB, ESCAPE, BACKSPACE, DELETE, INSERT, UP, DOWN, LEFT, RIGHT,
        HOME, END, PAGE_UP, PAGE_DOWN, F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
    }

    data class Modifiers(val ctrl: Boolean = false, val alt: Boolean = false, val shift: Boolean = false) {
        val parameter: Int get() = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
    }

    fun wireName(key: Key, modifiers: Modifiers = Modifiers()): String =
        (if (modifiers.ctrl) "ctrl+" else "") + (if (modifiers.alt) "alt+" else "") + (if (modifiers.shift) "shift+" else "") +
            when (key) {
                Key.ESCAPE -> "esc"
                Key.PAGE_UP -> "pageup"
                Key.PAGE_DOWN -> "pagedown"
                else -> key.name.lowercase()
            }

    /** SGR wheel reports are emitted only for an explicit local scroll gesture. */
    fun mouseWheel(older: Boolean): ByteArray = "\u001B[<${if (older) 64 else 65};1;1M".toByteArray(Charsets.US_ASCII)

    fun key(key: Key, modifiers: Modifiers = Modifiers(), applicationCursor: Boolean = false): ByteArray {
        val parameter = modifiers.parameter
        fun cursor(final: Char): String = when {
            parameter != 1 -> "\u001B[1;$parameter$final"
            applicationCursor -> "\u001BO$final"
            else -> "\u001B[$final"
        }
        fun tilde(number: Int): String = if (parameter == 1) "\u001B[$number~" else "\u001B[$number;$parameter~"
        val value = when (key) {
            Key.ENTER -> "\r"
            Key.TAB -> if (modifiers.shift) "\u001B[Z" else "\t"
            Key.ESCAPE -> "\u001B"
            Key.BACKSPACE -> "\u007F"
            Key.DELETE -> tilde(3)
            Key.INSERT -> tilde(2)
            Key.UP -> cursor('A')
            Key.DOWN -> cursor('B')
            Key.RIGHT -> cursor('C')
            Key.LEFT -> cursor('D')
            Key.HOME -> cursor('H')
            Key.END -> cursor('F')
            Key.PAGE_UP -> tilde(5)
            Key.PAGE_DOWN -> tilde(6)
            Key.F1, Key.F2, Key.F3, Key.F4 -> {
                val final = ('P'.code + key.ordinal - Key.F1.ordinal).toChar()
                if (parameter == 1) "\u001BO$final" else "\u001B[1;$parameter$final"
            }
            Key.F5 -> tilde(15)
            Key.F6 -> tilde(17)
            Key.F7 -> tilde(18)
            Key.F8 -> tilde(19)
            Key.F9 -> tilde(20)
            Key.F10 -> tilde(21)
            Key.F11 -> tilde(23)
            Key.F12 -> tilde(24)
        }
        val altPrefix = modifiers.alt && key in setOf(Key.ENTER, Key.TAB, Key.BACKSPACE)
        return ((if (altPrefix) "\u001B" else "") + value).toByteArray(Charsets.UTF_8)
    }

    /** A one-shot screen modifier applies to the next code point, not an entire IME completion. */
    fun text(text: String, modifiers: Modifiers = Modifiers()): ByteArray? {
        if (text.isEmpty()) return byteArrayOf()
        if (text.length > 8192 || !hasValidTerminalUnicode(text)) return null
        val first = text.codePointAt(0)
        val firstLength = Character.charCount(first)
        val firstText = if (modifiers.ctrl) {
            val control = when (first) {
                ' '.code, '@'.code, '2'.code -> 0
                '?'.code, '8'.code -> 127
                '3'.code -> 27
                '4'.code -> 28
                '5'.code -> 29
                '6'.code -> 30
                '7'.code -> 31
                in 'a'.code..'z'.code -> first - 'a'.code + 1
                in '@'.code..'_'.code -> first and 31
                else -> return null
            }
            control.toChar().toString()
        } else String(Character.toChars(first))
        return ((if (modifiers.alt) "\u001B" else "") + firstText + text.substring(firstLength)).toByteArray(Charsets.UTF_8)
    }
}

private fun hasValidTerminalUnicode(text: String): Boolean {
    var index = 0
    while (index < text.length) {
        val character = text[index]
        if (character.isHighSurrogate()) {
            if (index + 1 >= text.length || !text[index + 1].isLowSurrogate()) return false
            index += 2
        } else {
            if (character.isLowSurrogate()) return false
            index++
        }
    }
    return true
}

/** Tracks only explicitly advertised input modes, with a bounded incremental ANSI parser. */
internal class TerminalInputModes {
    var applicationCursor: Boolean = false
        private set
    var bracketedPaste: Boolean = false
        private set
    var sgrMouse: Boolean = false
        private set
    private val mouseTrackingModes = mutableSetOf<String>()
    val supportsSgrWheel: Boolean get() = sgrMouse && mouseTrackingModes.isNotEmpty()
    private var state = 0
    private val sequence = StringBuilder()

    fun observe(bytes: ByteArray, reset: Boolean = false) {
        if (reset) {
            clearModes()
            state = 0
            sequence.setLength(0)
        }
        for (byte in bytes) {
            val value = byte.toInt() and 255
            when (state) {
                0 -> if (value == 27) state = 1
                1 -> when (value) {
                    '['.code -> { state = 2; sequence.setLength(0) }
                    ']'.code, 'P'.code, '_'.code, '^'.code, 'X'.code -> state = 3
                    'c'.code -> { clearModes(); state = 0 }
                    27 -> Unit
                    else -> state = 0
                }
                2 -> when {
                    value in 0x40..0x7e -> {
                        if (value == 'h'.code || value == 'l'.code) applyMode(value == 'h'.code)
                        state = 0
                    }
                    value == 27 -> { state = 1; sequence.setLength(0) }
                    value in 0x20..0x3f && sequence.length < 64 -> sequence.append(value.toChar())
                    else -> { state = 0; sequence.setLength(0) }
                }
                3 -> when (value) { 7 -> state = 0; 27 -> state = 4 }
                4 -> state = if (value == '\\'.code || value == 7) 0 else 3
            }
        }
    }

    private fun clearModes() {
        applicationCursor = false
        bracketedPaste = false
        sgrMouse = false
        mouseTrackingModes.clear()
    }

    private fun applyMode(enabled: Boolean) {
        if (!sequence.startsWith("?")) return
        sequence.substring(1).split(';').forEach { mode ->
            when (mode) {
                "1" -> applicationCursor = enabled
                "2004" -> bracketedPaste = enabled
                "1000", "1002", "1003" -> if (enabled) mouseTrackingModes.add(mode) else mouseTrackingModes.remove(mode)
                "1006" -> sgrMouse = enabled
            }
        }
    }
}
