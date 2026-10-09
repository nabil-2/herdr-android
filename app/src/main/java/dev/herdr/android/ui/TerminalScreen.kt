package dev.herdr.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.android.data.ConnectionState
import dev.herdr.android.data.HerdrUiState
import dev.herdr.android.HerdrViewModel
import org.connectbot.terminal.Terminal
import org.connectbot.terminal.TerminalEmulatorFactory

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalScreen(state: HerdrUiState, viewModel: HerdrViewModel, onBack: () -> Unit, onRetry: (String) -> Unit, returnToChat: Boolean = false, fallbackReason: String? = null) {
    val paneId = state.paneId ?: return
    val pane = state.snapshot?.panes?.firstOrNull { it.id == paneId }
    val workspace = state.snapshot?.workspaces?.firstOrNull { it.id == pane?.workspaceId }
    val tab = state.snapshot?.tabs?.firstOrNull { it.id == pane?.tabId }
    val foreground = Color(0xFFE1E8EF)
    val emulator = remember(paneId) {
        TerminalEmulatorFactory.create(
            initialRows = state.terminal.rows,
            initialCols = state.terminal.cols,
            defaultForeground = foreground,
            defaultBackground = TerminalColor,
            // Emulator-generated answers to terminal queries never become remote input.
            onKeyboardInput = {},
        )
    }
    var receivedFrame by remember(paneId) { mutableStateOf(false) }
    var sessionId by remember(paneId) { mutableLongStateOf(-1L) }
    LaunchedEffect(paneId, emulator) {
        // Collection owns an observer subscription. Cancelling it closes the stream.
        viewModel.terminalFrames.collect { frame ->
            if (frame.paneId == paneId) {
                if (frame.reset || frame.sessionId != sessionId) {
                    emulator.writeInput("\u001Bc".toByteArray(Charsets.US_ASCII))
                    sessionId = frame.sessionId
                }
                if (emulator.dimensions.rows != frame.rows || emulator.dimensions.columns != frame.cols) {
                    emulator.resize(frame.rows, frame.cols)
                }
                emulator.writeInput(frame.ansi)
                receivedFrame = true
            }
        }
    }
    BackHandler(onBack = onBack)
    val canSend = state.connection == ConnectionState.CONNECTED && state.terminal.connected && !state.sending
    Scaffold(
        containerColor = TerminalColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pane?.title ?: "Terminal", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(workspace?.label, tab?.label).joinToString(" / "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { SymbolIcon(Symbol.BACK, if (returnToChat) "Back to conversation" else "Back to workspaces") } },
                actions = {
                    if (returnToChat) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("return-to-chat")) { Text("Chat") }
                    else if (pane != null) Box(Modifier.padding(end = 12.dp)) { StatusPill(pane.status) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasColor),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (fallbackReason != null && !returnToChat) Text(fallbackReason, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().background(CanvasColor).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val connectionColor = if (state.terminal.connected) Mint else Amber
                Box(Modifier.size(6.dp).background(connectionColor, CircleShape))
                Text(
                    if (state.terminal.connected) "Live" else if (state.terminal.connecting) "Reconnecting…" else "Disconnected",
                    color = connectionColor, style = MaterialTheme.typography.labelSmall,
                )
                Text("${state.terminal.cols} × ${state.terminal.rows}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (!state.terminal.connected && !state.terminal.connecting) {
                    TextButton(onClick = { onRetry(paneId) }) { Text("Reconnect") }
                } else {
                    Text("Pinch to zoom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.error != null) ErrorNotice(state.error, viewModel::clearError, Modifier.padding(8.dp))
            Box(Modifier.weight(1f).fillMaxWidth().background(TerminalColor)) {
                Terminal(
                    terminalEmulator = emulator,
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "Live terminal output for ${pane?.title ?: "selected pane"}" },
                    forcedSize = state.terminal.rows to state.terminal.cols,
                    backgroundColor = TerminalColor,
                    foregroundColor = foreground,
                    keyboardEnabled = false,
                    showSoftKeyboard = false,
                )
                if (!receivedFrame) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.terminal.connecting) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(if (state.terminal.connecting) "Opening terminal…" else "Waiting for terminal output", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            SpecialKeyBar(enabled = canSend, onKey = { viewModel.sendKeys(it) })
            CommandComposer(
                paneId = paneId,
                enabled = canSend,
                sending = state.sending,
                inputRevision = state.inputRevision,
                onSend = { text, enter -> viewModel.sendText(text, enter) },
            )
        }
    }
}

internal data class TerminalKey(val label: String, val wireName: String, val description: String = label)

internal val terminalKeys = listOf(
    TerminalKey("Enter", "enter"),
    TerminalKey("Esc", "esc", "Escape"),
    TerminalKey("Tab", "tab"),
    TerminalKey("↑", "up", "Up arrow"),
    TerminalKey("↓", "down", "Down arrow"),
    TerminalKey("←", "left", "Left arrow"),
    TerminalKey("→", "right", "Right arrow"),
    TerminalKey("Ctrl+C", "ctrl+c", "Control C, interrupt"),
)

@Composable
internal fun SpecialKeyBar(enabled: Boolean, onKey: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().background(CanvasColor),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(terminalKeys, key = { it.wireName }) { key ->
            OutlinedButton(
                onClick = { onKey(key.wireName) },
                enabled = enabled,
                contentPadding = PaddingValues(horizontal = 12.dp),
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = key.description }.testTag("key-${key.wireName}"),
            ) { Text(key.label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelMedium) }
        }
    }
}

@Composable
internal fun CommandComposer(paneId: String, enabled: Boolean, sending: Boolean, inputRevision: Long, onSend: (String, Boolean) -> Unit) {
    // Drafts, like credentials, stay in memory and are excluded from saved app state.
    var draft by remember(paneId) { mutableStateOf("") }
    var appendEnter by rememberSaveable(paneId) { mutableStateOf(true) }
    var pendingDraft by remember(paneId) { mutableStateOf<String?>(null) }
    var pendingRevision by remember(paneId) { mutableLongStateOf(inputRevision) }
    LaunchedEffect(inputRevision) {
        if (inputRevision > pendingRevision && pendingDraft != null) {
            if (draft == pendingDraft) draft = ""
            pendingDraft = null
        }
    }
    Column(Modifier.fillMaxWidth().background(CanvasColor).padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                placeholder = { Text("Command or agent prompt") },
                maxLines = 4,
                enabled = !sending,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Default),
                modifier = Modifier.weight(1f).testTag("command-draft"),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Button(
                enabled = enabled && draft.isNotEmpty(),
                onClick = {
                    pendingDraft = draft
                    pendingRevision = inputRevision
                    onSend(draft, appendEnter)
                },
                shape = MaterialTheme.shapes.medium,
                contentPadding = PaddingValues(horizontal = 16.dp),
                modifier = Modifier.heightIn(min = 56.dp).testTag("send-command"),
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Send", fontWeight = FontWeight.SemiBold)
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier.weight(1f).heightIn(min = 48.dp).clickable(role = Role.Checkbox, enabled = !sending) { appendEnter = !appendEnter }.testTag("append-enter"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = appendEnter, onCheckedChange = null, enabled = !sending)
                Spacer(Modifier.width(6.dp))
                Text("Enter after send", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { draft = ""; pendingDraft = null }, enabled = draft.isNotEmpty() && !sending) { Text("Clear") }
        }
    }
}
