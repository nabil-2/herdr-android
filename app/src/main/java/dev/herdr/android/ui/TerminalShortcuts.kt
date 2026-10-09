package dev.herdr.android.ui

/** Encode button keys with the same rules as the native IME and hardware keyboard. */
internal fun terminalShortcutBytes(name: String, ctrl: Boolean, alt: Boolean, modes: TerminalInputModes): ByteArray? {
    val parts = name.lowercase().split('+')
    val modifiers = TerminalInputEncoder.Modifiers(ctrl = ctrl || "ctrl" in parts, alt = alt || "alt" in parts, shift = "shift" in parts)
    val final = parts.last()
    val key = when (final) {
        "enter" -> TerminalInputEncoder.Key.ENTER
        "tab" -> TerminalInputEncoder.Key.TAB
        "esc" -> TerminalInputEncoder.Key.ESCAPE
        "backspace" -> TerminalInputEncoder.Key.BACKSPACE
        "delete" -> TerminalInputEncoder.Key.DELETE
        "insert" -> TerminalInputEncoder.Key.INSERT
        "up" -> TerminalInputEncoder.Key.UP
        "down" -> TerminalInputEncoder.Key.DOWN
        "left" -> TerminalInputEncoder.Key.LEFT
        "right" -> TerminalInputEncoder.Key.RIGHT
        "home" -> TerminalInputEncoder.Key.HOME
        "end" -> TerminalInputEncoder.Key.END
        "pageup" -> TerminalInputEncoder.Key.PAGE_UP
        "pagedown" -> TerminalInputEncoder.Key.PAGE_DOWN
        else -> TerminalInputEncoder.Key.entries.firstOrNull { it.name.lowercase() == final }
    }
    return if (key != null) TerminalInputEncoder.key(key, modifiers, modes.applicationCursor)
    else if (final.length == 1) TerminalInputEncoder.text(final, modifiers) else null
}
