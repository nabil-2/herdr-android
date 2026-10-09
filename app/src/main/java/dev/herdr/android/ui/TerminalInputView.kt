package dev.herdr.android.ui

import android.content.Context
import android.graphics.Rect
import android.text.InputType
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

internal interface TerminalInputController {
    /** The screen's extra keys share the IME's composition and refusal handling. */
    fun sendKey(key: String): Boolean
}

/** Native terminal editor. It has no path from terminal output to its input callback. */
internal class TerminalInputView(context: Context) : View(context), TerminalInputController {
    var onInput: (ByteArray) -> Boolean = { false }
    var onKeyInput: ((List<String>) -> Boolean)? = null
    var onModifierConsumed: () -> Unit = {}
    var onComposingChanged: (String) -> Unit = {}
    var onPasteRequest: () -> Unit = {}
    var onPasteTextRequest: ((String) -> Unit)? = null
    var ctrlPressed = false
    var altPressed = false
    var inputModes = TerminalInputModes()
    private var inputEnabled = false
    private var requestedKeyboard = false
    private var composing = ""
    private var deadAccent = 0
    private var connectionGeneration = 0L
    private var keyboardRequestGeneration = 0L
    private val inputMethodManager get() = context.getSystemService(InputMethodManager::class.java)

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Terminal keyboard input"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    fun configure(enabled: Boolean, showKeyboard: Boolean) {
        val wasEnabled = inputEnabled
        inputEnabled = enabled
        if (wasEnabled && !enabled) connectionGeneration++
        if (!enabled) {
            hideKeyboard()
            clearFocus()
            clearComposition()
        } else {
            if (!wasEnabled) requestFocus()
            if (showKeyboard && (!requestedKeyboard || !wasEnabled)) requestKeyboard()
            else if (!showKeyboard && requestedKeyboard) hideKeyboard()
        }
        requestedKeyboard = showKeyboard && enabled
    }

    fun requestKeyboard() {
        if (!inputEnabled) return
        requestFocus()
        val generation = ++keyboardRequestGeneration
        post {
            if (inputEnabled && isAttachedToWindow && generation == keyboardRequestGeneration)
                inputMethodManager.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    fun hideKeyboard() {
        keyboardRequestGeneration++
        inputMethodManager.hideSoftInputFromWindow(windowToken, 0)
    }

    fun dispose() {
        connectionGeneration++
        inputEnabled = false
        hideKeyboard()
        clearFocus()
        clearComposition()
        onInput = { false }
    }

    private fun clearComposition() {
        composing = ""
        deadAccent = 0
        onComposingChanged("")
    }

    override fun onCheckIsTextEditor(): Boolean = inputEnabled

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (!gainFocus && inputEnabled) {
            connectionGeneration++
            clearComposition()
        }
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus && inputEnabled) {
            connectionGeneration++
            clearComposition()
        }
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!inputEnabled) return null
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        val generation = ++connectionGeneration
        return object : BaseInputConnection(this, false) {
            private fun current(): Boolean = inputEnabled && generation == connectionGeneration

            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!current()) return false
                val value = text?.toString().orEmpty()
                val previousComposition = composing
                if ((value == "\n" || value == "\r") && composing == value) composing = ""
                val accepted = emitText(value)
                if (accepted) clearComposition()
                else composing = previousComposition
                return accepted
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                if (!current()) return false
                val value = text?.toString().orEmpty()
                if (value.length > 8192) return false
                composing = value
                onComposingChanged(value)
                return true
            }

            override fun finishComposingText(): Boolean {
                if (!current()) return false
                return flushComposition()
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = current() && deleteText(beforeLength, afterLength)
            override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = current() && deleteText(beforeLength, afterLength)
            override fun sendKeyEvent(event: KeyEvent): Boolean = current() && handleKey(event)
            override fun performEditorAction(actionCode: Int): Boolean = current() && emitKey(TerminalInputEncoder.Key.ENTER)
            override fun performContextMenuAction(id: Int): Boolean {
                if (!current()) return false
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                    onPasteRequest()
                    return true
                }
                return false
            }
            override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = if (current()) composing.takeLast(n.coerceIn(0, 8192)) else ""
            override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""
            override fun getSelectedText(flags: Int): CharSequence = ""
            override fun setSelection(start: Int, end: Int): Boolean = current() && start == end
            override fun setComposingRegion(start: Int, end: Int): Boolean = current() && start in 0..composing.length && end in start..composing.length
        }
    }

    private fun deleteText(before: Int, after: Int): Boolean {
        if (!inputEnabled || before !in 0..32 || after !in 0..32 || before + after > 32) return false
        if (composing.isNotEmpty()) {
            val count = composing.codePointCount(0, composing.length)
            val keep = (count - before).coerceAtLeast(0)
            composing = composing.substring(0, composing.offsetByCodePoints(0, keep))
            onComposingChanged(composing)
            return true
        }
        if (before + after == 0) return true
        val keys = List(before) { "backspace" } + List(after) { "delete" }
        val logical = onKeyInput
        return if (logical != null) logical(keys) else onInput(("\u007F".repeat(before) + "\u001B[3~".repeat(after)).toByteArray(Charsets.US_ASCII))
    }

    private fun flushComposition(): Boolean {
        if (composing.isEmpty()) return true
        val value = composing
        if (value.length > 1 && value.any { it.code < 32 || it.code == 127 }) {
            val preview = onPasteTextRequest ?: return false
            preview(value)
            clearComposition()
            // A local preview is not a remote write. Do not follow it with an
            // Enter/navigation action while the user is reviewing the text.
            return false
        }
        // Screen modifiers may have been selected after this word was composed.
        // They belong to the requested key, not to the pending ordinary text.
        val accepted = if (value == "\n" || value == "\r") forwardKey("enter")
            else emitText(value, ctrl = false, alt = false, consume = false)
        if (accepted) clearComposition()
        return accepted
    }

    private fun emitText(text: String, ctrl: Boolean = ctrlPressed, alt: Boolean = altPressed, consume: Boolean = true): Boolean {
        if (!inputEnabled) return false
        if (text.length > 32768) return false
        if (text == "\n" || text == "\r") return emitKey(TerminalInputEncoder.Key.ENTER)
        // IMEs can insert clipboard text directly, bypassing Android's context
        // menu. Review a multiline/control-containing insertion before it can
        // submit commands or invoke terminal escape sequences.
        if (text.length > 1 && text.any { it.code < 32 || it.code == 127 }) {
            val preview = onPasteTextRequest ?: return false
            preview(text)
            return true
        }
        val bytes = TerminalInputEncoder.text(text, TerminalInputEncoder.Modifiers(ctrl, alt)) ?: return false
        if (bytes.isEmpty()) return true
        return onInput(bytes).also { if (it && consume) consumeModifiers() }
    }

    private fun emitKey(key: TerminalInputEncoder.Key, event: KeyEvent? = null): Boolean {
        if (!inputEnabled) return false
        val modifiers = TerminalInputEncoder.Modifiers(
            ctrlPressed || event?.isCtrlPressed == true,
            altPressed || event?.isAltPressed == true,
            event?.isShiftPressed == true,
        )
        return sendKey(TerminalInputEncoder.wireName(key, modifiers))
    }

    override fun sendKey(key: String): Boolean {
        if (!inputEnabled || key.length !in 1..32 || key.any { !it.isAsciiPrintableKey() }) return false
        val name = key.lowercase()
        val base = name.substringAfterLast('+')
        val ctrl = name.split('+').contains("ctrl")
        if (base == "enter" && (composing == "\n" || composing == "\r")) {
            val previous = composing
            composing = ""
            val accepted = forwardKey(name)
            if (accepted) { clearComposition(); consumeModifiers() } else composing = previous
            return accepted
        }
        if ((ctrl && base == "c") || base == "esc" || base == "escape") clearComposition()
        else if (composing.isNotEmpty() && base == "backspace") {
            if (ctrl) clearComposition() else deleteText(1, 0)
            consumeModifiers()
            return true
        } else if (composing.isNotEmpty() && base == "delete") {
            consumeModifiers()
            return true
        } else if (!flushComposition()) return false
        return forwardKey(name).also { if (it) consumeModifiers() }
    }

    private fun Char.isAsciiPrintableKey(): Boolean = code in 33..126

    private fun forwardKey(name: String): Boolean {
        // Herdr0.9.3 does not accept its logical pageup/pagedown spellings.
        // Preserve native composition/refusal handling, then send these two
        // standard sequences through the same scoped raw-input queue as typing.
        if (name.substringAfterLast('+') in setOf("pageup", "pagedown")) {
            return terminalShortcutBytes(name, false, false, inputModes)?.let(onInput) ?: false
        }
        onKeyInput?.let { return it(listOf(name)) }
        // Fallback supports isolated adapter tests. Production always supplies
        // logical key transport because observer snapshots omit PTY input modes.
        val parts = name.split('+')
        val modifiers = TerminalInputEncoder.Modifiers("ctrl" in parts, "alt" in parts, "shift" in parts)
        val base = parts.last()
        val key = when (base) {
            "esc", "escape" -> TerminalInputEncoder.Key.ESCAPE
            "pageup" -> TerminalInputEncoder.Key.PAGE_UP
            "pagedown" -> TerminalInputEncoder.Key.PAGE_DOWN
            else -> TerminalInputEncoder.Key.entries.firstOrNull { it.name.lowercase() == base }
        }
        val bytes = if (key != null) TerminalInputEncoder.key(key, modifiers, inputModes.applicationCursor)
            else if (base.codePointCount(0, base.length) == 1) TerminalInputEncoder.text(base, modifiers) else null
        return bytes != null && onInput(bytes)
    }

    private fun consumeModifiers() {
        // Clear locally before Compose's next frame so fast hardware repeats cannot
        // consume a one-shot screen modifier more than once.
        ctrlPressed = false
        altPressed = false
        onModifierConsumed()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = knownKey(keyCode) != null || super.onKeyUp(keyCode, event)
    override fun onKeyMultiple(keyCode: Int, repeatCount: Int, event: KeyEvent): Boolean = handleKey(event) || super.onKeyMultiple(keyCode, repeatCount, event)

    private fun handleKey(event: KeyEvent): Boolean {
        if (!inputEnabled) return false
        if (event.action == KeyEvent.ACTION_UP) return knownKey(event.keyCode) != null
        if (event.action == KeyEvent.ACTION_MULTIPLE && event.characters != null) return flushComposition() && emitText(event.characters)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if ((event.keyCode == KeyEvent.KEYCODE_V && event.isCtrlPressed && event.isShiftPressed && !event.isAltPressed) ||
            (event.keyCode == KeyEvent.KEYCODE_INSERT && event.isShiftPressed && !event.isCtrlPressed && !event.isAltPressed)) {
            onPasteRequest()
            return true
        }
        knownKey(event.keyCode)?.let { return emitKey(it, event) }
        // Android reports Ctrl characters differently between physical keyboards. Encode
        // from the printable base character and apply Ctrl once, instead of duplicating it.
        val altGr = (event.metaState and KeyEvent.META_ALT_RIGHT_ON) != 0
        val printableMeta = event.metaState and KeyEvent.META_CTRL_MASK.inv()
        val meta = if (altGr) printableMeta else printableMeta and KeyEvent.META_ALT_MASK.inv()
        var codePoint = event.getUnicodeChar(meta)
        if (codePoint == 0) return false
        val ctrl = ctrlPressed || (event.isCtrlPressed && !altGr)
        val alt = altPressed || (event.isAltPressed && !altGr)
        if (ctrl && (codePoint == 'c'.code || codePoint == 'C'.code)) {
            clearComposition()
            return emitText(String(Character.toChars(codePoint)), ctrl, alt)
        }
        if ((codePoint and KeyCharacterMap.COMBINING_ACCENT) != 0) {
            deadAccent = codePoint and KeyCharacterMap.COMBINING_ACCENT_MASK
            return true
        }
        if (deadAccent != 0) {
            val combined = KeyCharacterMap.getDeadChar(deadAccent, codePoint)
            val accent = deadAccent
            deadAccent = 0
            if (combined != 0) codePoint = combined
            else return emitText(String(Character.toChars(accent)) + String(Character.toChars(codePoint)), ctrlPressed, altPressed)
        }
        if (!flushComposition()) return false
        return emitText(String(Character.toChars(codePoint)), ctrl, alt)
    }

    private fun knownKey(code: Int): TerminalInputEncoder.Key? = when (code) {
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> TerminalInputEncoder.Key.ENTER
        KeyEvent.KEYCODE_TAB -> TerminalInputEncoder.Key.TAB
        KeyEvent.KEYCODE_ESCAPE -> TerminalInputEncoder.Key.ESCAPE
        KeyEvent.KEYCODE_DEL -> TerminalInputEncoder.Key.BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> TerminalInputEncoder.Key.DELETE
        KeyEvent.KEYCODE_INSERT -> TerminalInputEncoder.Key.INSERT
        KeyEvent.KEYCODE_DPAD_UP -> TerminalInputEncoder.Key.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> TerminalInputEncoder.Key.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> TerminalInputEncoder.Key.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> TerminalInputEncoder.Key.RIGHT
        KeyEvent.KEYCODE_MOVE_HOME -> TerminalInputEncoder.Key.HOME
        KeyEvent.KEYCODE_MOVE_END -> TerminalInputEncoder.Key.END
        KeyEvent.KEYCODE_PAGE_UP -> TerminalInputEncoder.Key.PAGE_UP
        KeyEvent.KEYCODE_PAGE_DOWN -> TerminalInputEncoder.Key.PAGE_DOWN
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> TerminalInputEncoder.Key.entries[TerminalInputEncoder.Key.F1.ordinal + code - KeyEvent.KEYCODE_F1]
        else -> null
    }
}
