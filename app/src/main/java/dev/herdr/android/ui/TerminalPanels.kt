package dev.herdr.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.android.data.Pane

internal enum class TerminalPanel { TOOLS, KEYS, COMPOSE, APPEARANCE, PANES, OUTPUT }

internal val terminalNavigationKeys = listOf(
    TerminalKey("Home", "home"), TerminalKey("End", "end"),
    TerminalKey("PgUp", "pageup", "Page up"), TerminalKey("PgDn", "pagedown", "Page down"),
    TerminalKey("Insert", "insert"), TerminalKey("Delete", "delete"),
    TerminalKey("Backspace", "backspace"), TerminalKey("Shift+Tab", "shift+tab"),
)

internal val terminalControlKeys = listOf(
    TerminalKey("Ctrl+C", "ctrl+c", "Control C, interrupt"),
    TerminalKey("Ctrl+D", "ctrl+d", "Control D, end of input"),
    TerminalKey("Ctrl+Z", "ctrl+z", "Control Z, suspend"),
    TerminalKey("Ctrl+L", "ctrl+l", "Control L, clear screen"),
    TerminalKey("Ctrl+A", "ctrl+a", "Control A, beginning of line"),
    TerminalKey("Ctrl+E", "ctrl+e", "Control E, end of line"),
    TerminalKey("Ctrl+R", "ctrl+r", "Control R, search shell history"),
    TerminalKey("Ctrl+W", "ctrl+w", "Control W, erase word"),
)

@Composable
internal fun CompactTerminalKeys(
    enabled: Boolean, ctrlPressed: Boolean, altPressed: Boolean,
    onCtrl: () -> Unit, onAlt: () -> Unit, onKey: (String) -> Unit, onMore: () -> Unit,
) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(CanvasColor).testTag("terminal-key-row"),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(listOf(TerminalKey("Esc", "esc", "Escape"), TerminalKey("Tab", "tab"))) { key ->
            TerminalKeyButton(key, enabled, onKey)
        }
        item { FilterChip(selected = ctrlPressed, onClick = onCtrl, enabled = enabled, label = { Text("Ctrl", fontFamily = FontFamily.Monospace) }, modifier = Modifier.heightIn(min = 48.dp).testTag("terminal-ctrl")) }
        item { FilterChip(selected = altPressed, onClick = onAlt, enabled = enabled, label = { Text("Alt", fontFamily = FontFamily.Monospace) }, modifier = Modifier.heightIn(min = 48.dp).testTag("terminal-alt")) }
        items(listOf(TerminalKey("←", "left", "Left arrow"), TerminalKey("↓", "down", "Down arrow"), TerminalKey("↑", "up", "Up arrow"), TerminalKey("→", "right", "Right arrow"), TerminalKey("↵", "enter", "Enter"))) { key ->
            TerminalKeyButton(key, enabled, onKey)
        }
        item { TextButton(onClick = onMore, modifier = Modifier.heightIn(min = 48.dp).testTag("terminal-more-keys")) { Text("More") } }
    }
}

@Composable
private fun TerminalKeyButton(key: TerminalKey, enabled: Boolean, onKey: (String) -> Unit) {
    OutlinedButton(
        onClick = { onKey(key.wireName) }, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 12.dp), shape = MaterialTheme.shapes.small,
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = key.description }.testTag("key-${key.wireName}"),
    ) { Text(key.label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalToolsSheet(
    onClose: () -> Unit, onCompose: () -> Unit, onKeys: () -> Unit, onAppearance: () -> Unit,
    onPaste: () -> Unit, onSelect: () -> Unit, onFocus: () -> Unit, focusMode: Boolean, canPaste: Boolean,
    onOutput: () -> Unit = {}, canReadOutput: Boolean = false,
) {
    TerminalSheet("Terminal tools", onClose) {
        ToolRow("Compose command", "Draft or reuse text before sending", "terminal-compose", onCompose)
        ToolRow("Paste", "Review clipboard text before inserting", "terminal-paste", onPaste, canPaste)
        ToolRow("Select output", "Copy text from the visible terminal", "terminal-select", onSelect)
        ToolRow("Output history", "Read, find and copy recent terminal output", "terminal-output", onOutput, canReadOutput)
        ToolRow("Special keys", "Navigation, function keys and shortcuts", "terminal-special-keys", onKeys)
        ToolRow("Appearance", "Font, colors and viewport", "terminal-appearance", onAppearance)
        ToolRow(if (focusMode) "Leave focus mode" else "Focus mode", "More room for terminal output", "terminal-focus", onFocus)
    }
}

@Composable
private fun ToolRow(title: String, subtitle: String, tag: String, onClick: () -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onClick).heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        SymbolIcon(Symbol.NEXT, modifier = Modifier.size(18.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalKeysSheet(enabled: Boolean, onClose: () -> Unit, onKey: (String) -> Unit) {
    TerminalSheet("Special keys", onClose) {
        Text("Keys act on this pane only. Ctrl and Alt in the key row apply to the next key.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        KeySection("Navigation", terminalNavigationKeys, enabled, onKey)
        KeySection("Shortcuts", terminalControlKeys, enabled, onKey)
        KeySection("Function keys", (1..12).map { TerminalKey("F$it", "f$it") }, enabled, onKey)
    }
}

@Composable
private fun KeySection(title: String, keys: List<TerminalKey>, enabled: Boolean, onKey: (String) -> Unit) {
    Text(title, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    keys.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            row.forEach { key -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { TerminalKeyButton(key, enabled, onKey) } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalAppearanceSheet(appearance: TerminalAppearance, onChange: (TerminalAppearance) -> Unit, onResetView: () -> Unit, onClose: () -> Unit) {
    TerminalSheet("Terminal appearance", onClose) {
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Font size", style = MaterialTheme.typography.titleSmall)
                Text("${appearance.fontSize.toInt()} sp", fontFamily = FontFamily.Monospace, color = Mint)
            }
            Slider(value = appearance.fontSize, onValueChange = { onChange(appearance.copy(fontSize = it)) }, valueRange = 8f..28f, steps = 19, modifier = Modifier.testTag("terminal-font-size"))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Fit all columns", style = MaterialTheme.typography.titleSmall)
                    Text("Turn off for a readable font; drag to see more columns.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = appearance.fitToWidth, onCheckedChange = { onChange(appearance.copy(fitToWidth = it)) }, modifier = Modifier.testTag("terminal-fit-width"))
            }
            Text("Colors", style = MaterialTheme.typography.titleSmall)
            TerminalPalette.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { palette ->
                        FilterChip(
                            selected = appearance.theme == palette,
                            onClick = { onChange(appearance.copy(theme = palette)) },
                            label = { Text(palette.label) },
                            leadingIcon = { Box(Modifier.size(16.dp).background(palette.background, CircleShape)) },
                            modifier = Modifier.weight(1f).testTag("terminal-theme-${palette.name}"),
                        )
                    }
                }
            }
            Box(Modifier.fillMaxWidth().background(appearance.theme.background, MaterialTheme.shapes.small).padding(16.dp)) {
                Text("user@server ~\n$ echo hello\nhello ▌", color = appearance.theme.foreground, fontFamily = FontFamily.Monospace)
            }
            Text("Pinch to change the local font. The desktop terminal keeps its size.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onResetView, modifier = Modifier.testTag("terminal-reset-view")) { Text("Reset scroll and zoom") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalPaneSheet(panes: List<Pane>, selectedId: String, onPane: (String) -> Unit, onClose: () -> Unit) {
    TerminalSheet("Switch session", onClose) {
        panes.forEach { pane ->
            Row(Modifier.fillMaxWidth().clickable { onPane(pane.id) }.heightIn(min = 64.dp).padding(horizontal = 20.dp, vertical = 8.dp).testTag("terminal-pane-${pane.id}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SymbolIcon(if (pane.conversation?.available == true) Symbol.CHAT else Symbol.TERMINAL, modifier = Modifier.size(20.dp))
                Column(Modifier.weight(1f)) {
                    Text(pane.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(if (pane.id == selectedId) "Current session" else pane.agent ?: "Terminal", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (pane.id == selectedId) Text("●", color = Mint) else SymbolIcon(Symbol.NEXT, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalComposeSheet(
    paneId: String, enabled: Boolean, sending: Boolean, inputRevision: Long,
    memory: TerminalCommandMemory, onMemoryChanged: () -> Unit,
    onSend: (String, Boolean) -> Unit, onClose: () -> Unit,
    insertion: TerminalDraftInsertion? = null,
    draftState: TerminalDraftState = remember(paneId) { TerminalDraftState(inputRevision) },
    memoryRevision: Int = 0,
) {
    // Plain memory deliberately holds no Compose or disk dependency; this revision observes edits.
    @Suppress("UNUSED_VARIABLE") val observedMemoryRevision = memoryRevision
    val draft by draftState.text
    var pickedInsertion by remember(paneId) { mutableStateOf<TerminalDraftInsertion?>(insertion) }
    var nextInsertion by remember(paneId) { mutableLongStateOf(insertion?.id ?: 0) }
    var saveDialog by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf("") }
    TerminalSheet("Compose command", onClose) {
        Text("Review text before sending. Choose whether to press Enter after it.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CommandComposer(
            paneId, enabled, sending, inputRevision,
            draftInsertion = pickedInsertion,
            draftState = draftState,
            onAcknowledged = { memory.acknowledgeComposedCommand(it); onMemoryChanged() },
            onSend = onSend,
        )
        TextButton(onClick = { saveDialog = true }, enabled = draft.isNotBlank() && draft.length <= 8192 && memory.snippets.size < 20, modifier = Modifier.padding(horizontal = 12.dp).testTag("terminal-save-snippet")) { Text("Save draft as snippet") }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Row(Modifier.padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Remember composed commands", style = MaterialTheme.typography.titleSmall)
                Text("Off by default. History stays in this session's memory. Live typing is never saved.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = memory.rememberCommands, onCheckedChange = { memory.rememberCommands = it; onMemoryChanged() }, modifier = Modifier.testTag("terminal-history-toggle"))
        }
        if (memory.history.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Recent commands", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                TextButton(onClick = { memory.clearHistory(); onMemoryChanged() }) { Text("Clear") }
            }
            memory.history.forEach { command ->
                ReusableCommandRow(command.lineSequence().firstOrNull().orEmpty(), "Insert into draft", { pickedInsertion = TerminalDraftInsertion(++nextInsertion, command) })
            }
        }
        Text("Saved snippets", Modifier.padding(start = 20.dp, top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.labelLarge)
        Text("Stored on this device until deleted. Avoid saving passwords or tokens.", Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (memory.snippets.isEmpty()) Text("Save a draft to reuse it here.", Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        memory.snippets.forEach { snippet ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { ReusableCommandRow(snippet.label, snippet.text.lineSequence().firstOrNull().orEmpty(), { pickedInsertion = TerminalDraftInsertion(++nextInsertion, snippet.text) }) }
                TextButton(onClick = { memory.deleteSnippet(snippet.id); onMemoryChanged() }, modifier = Modifier.semantics { contentDescription = "Delete snippet ${snippet.label}" }) { Text("Delete") }
            }
        }
    }
    if (saveDialog) AlertDialog(
        onDismissRequest = { saveDialog = false; label = "" },
        title = { Text("Save snippet") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Only this draft will be saved on your device. It will be inserted for review when reused.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(value = label, onValueChange = { label = it.take(48) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.testTag("terminal-snippet-name"))
        } },
        confirmButton = { TextButton(onClick = { if (memory.saveSnippet(label, draft)) { onMemoryChanged(); saveDialog = false; label = "" } }, enabled = label.isNotBlank(), modifier = Modifier.testTag("terminal-confirm-snippet")) { Text("Save") } },
        dismissButton = { TextButton(onClick = { saveDialog = false; label = "" }) { Text("Cancel") } },
    )
}

@Composable
private fun ReusableCommandRow(label: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 12.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontFamily = FontFamily.Monospace)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
internal fun TerminalPasteReview(text: String, enabled: Boolean, onInsert: (String) -> Unit, onCompose: (String) -> Unit, onClose: () -> Unit) {
    val safe = isSafeTerminalPaste(text)
    val containsLineBreak = text.any { it == '\n' || it == '\r' }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Review paste") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(when {
                    !safe -> "This clipboard text contains unsupported control characters or exceeds the input limit."
                    containsLineBreak -> "Line breaks can execute commands in a shell. Open this text in Compose to edit it before sending."
                    else -> "Insert this text into the current terminal without pressing Enter."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text.take(4096), Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()).background(TerminalColor, MaterialTheme.shapes.small).padding(12.dp).testTag("terminal-paste-preview"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = { if (containsLineBreak) onCompose(text) else onInsert(text) }, enabled = enabled && safe, modifier = Modifier.testTag("terminal-confirm-paste")) { Text(if (containsLineBreak) "Open in Compose" else "Insert text") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalSheet(title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onClose) { Text("Done") }
            }
            Spacer(Modifier.size(8.dp))
            content()
        }
    }
}
