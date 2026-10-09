package dev.herdr.android.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Appearance only: terminal keystrokes and session history never enter preferences. */
internal data class TerminalAppearance(
    val fontSize: Float = 14f,
    val theme: TerminalPalette = TerminalPalette.MIDNIGHT,
    val fitToWidth: Boolean = true,
)

internal enum class TerminalPalette(val label: String, val foreground: Color, val background: Color) {
    MIDNIGHT("Midnight", Color(0xFFE1E8EF), Color(0xFF080C11)),
    CHARCOAL("Charcoal", Color(0xFFE8E6E3), Color(0xFF202020)),
    SOLARIZED("Solarized", Color(0xFF93A1A1), Color(0xFF002B36)),
    PAPER("Paper", Color(0xFF20252B), Color(0xFFF6F2E9)),
}

internal class TerminalPreferenceStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("terminal_appearance", Context.MODE_PRIVATE)
    fun read(): TerminalAppearance = TerminalAppearance(
        fontSize = preferences.getFloat("font_size", 14f).takeIf { it.isFinite() }?.coerceIn(8f, 28f) ?: 14f,
        theme = TerminalPalette.entries.firstOrNull { it.name == preferences.getString("palette", null) } ?: TerminalPalette.MIDNIGHT,
        // The old default was saved whenever colors/font changed. Upgrade those
        // old defaults once; future explicit zoom/fit choices remain respected.
        fitToWidth = if (preferences.getBoolean("fit_width_default_v2", false)) preferences.getBoolean("fit_width", true) else true,
    )
    fun write(appearance: TerminalAppearance) {
        preferences.edit().putFloat("font_size", appearance.fontSize.coerceIn(8f, 28f))
            .putString("palette", appearance.theme.name).putBoolean("fit_width", appearance.fitToWidth)
            .putBoolean("fit_width_default_v2", true).apply()
    }
}

@Serializable
internal data class TerminalSnippet(val id: Long, val label: String, val text: String) {
    override fun toString(): String = "TerminalSnippet(id=$id, text=redacted)"
}

/** Snippets are saved only through the explicit Save action; Android backup is disabled. */
internal class TerminalSnippetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("terminal_snippets", Context.MODE_PRIVATE)
    fun read(): List<TerminalSnippet> = runCatching {
        Json.decodeFromString<List<TerminalSnippet>>(preferences.getString("entries", "[]") ?: "[]")
            .filter { it.label.isNotBlank() && it.text.isNotBlank() && it.text.length <= 8192 }.take(20)
    }.getOrDefault(emptyList())
    fun write(snippets: List<TerminalSnippet>) { preferences.edit().putString("entries", Json.encodeToString(snippets.take(20))).apply() }
}

/** Intentionally in memory. Only explicitly composed commands can be remembered. */
internal class TerminalCommandMemory(initialSnippets: List<TerminalSnippet> = emptyList()) {
    private var nextId = initialSnippets.maxOfOrNull { it.id } ?: 0L
    private val commands = mutableListOf<String>()
    private val saved = initialSnippets.take(20).toMutableList()
    var rememberCommands: Boolean = false
        set(value) { field = value; if (!value) commands.clear() }
    val history: List<String> get() = commands.toList()
    val snippets: List<TerminalSnippet> get() = saved.toList()

    fun acknowledgeComposedCommand(text: String) {
        if (!rememberCommands || text.isBlank() || text.length > 8192) return
        // A reused command must be exact; do not save a truncated executable.
        val remembered = text
        commands.remove(remembered)
        commands.add(0, remembered)
        if (commands.size > 20) commands.removeAt(commands.lastIndex)
    }

    fun saveSnippet(label: String, text: String): Boolean {
        if (label.isBlank() || text.isBlank() || text.length > 8192 || saved.size >= 20) return false
        saved.add(0, TerminalSnippet(++nextId, label.trim().take(48), text))
        return true
    }

    fun deleteSnippet(id: Long) { saved.removeAll { it.id == id } }
    fun clearHistory() { commands.clear() }
}

internal data class TerminalDraftInsertion(val id: Long, val text: String) {
    override fun toString(): String = "TerminalDraftInsertion(id=$id, text=redacted)"
}

/** A drawer may close while a write is in flight; the pane's draft remains in memory. */
internal class TerminalDraftState(inputRevision: Long = 0) {
    val text = mutableStateOf("")
    val appendEnter = mutableStateOf(true)
    val pendingDraft = mutableStateOf<String?>(null)
    val pendingRevision = mutableLongStateOf(inputRevision)
}

/** Preview controls cannot send these accidentally; live input is handled separately. */
internal fun isSafeTerminalPaste(text: String): Boolean = text.isNotEmpty() &&
    text.codePointCount(0, text.length) <= 16384 && text.toByteArray(Charsets.UTF_8).size <= 32768 &&
    text.none { Character.isISOControl(it) && it !in "\n\r\t" }
