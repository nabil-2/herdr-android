package dev.herdr.android.ui

import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.android.data.HerdrUiState
import dev.herdr.android.HerdrViewModel
import org.connectbot.terminal.TerminalEmulatorFactory
import org.connectbot.terminal.SelectionController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalScreen(state: HerdrUiState, viewModel: HerdrViewModel, onBack: () -> Unit, onRetry: (String) -> Unit, returnToChat: Boolean = false, fallbackReason: String? = null) {
    val paneId = state.paneId ?: return
    val pane = state.snapshot?.panes?.firstOrNull { it.id == paneId }
    val workspace = state.snapshot?.workspaces?.firstOrNull { it.id == pane?.workspaceId }
    val tab = state.snapshot?.tabs?.firstOrNull { it.id == pane?.tabId }
    val context = LocalContext.current
    val appearanceStore = remember(context) { TerminalPreferenceStore(context) }
    val snippetStore = remember(context) { TerminalSnippetStore(context) }
    var appearance by remember { mutableStateOf(appearanceStore.read()) }
    val commandMemory = remember(state.activeProfileId, paneId) { TerminalCommandMemory(snippetStore.read()) }
    val composerDraft = remember(state.activeProfileId, paneId) { TerminalDraftState(state.inputRevision) }
    var memoryRevision by remember(state.activeProfileId, paneId) { mutableStateOf(0) }
    var panel by remember(paneId) { mutableStateOf<TerminalPanel?>(null) }
    var keyboardVisible by remember(paneId) { mutableStateOf(false) }
    var focusMode by rememberSaveable { mutableStateOf(false) }
    var ctrlPressed by remember(paneId) { mutableStateOf(false) }
    var altPressed by remember(paneId) { mutableStateOf(false) }
    var pastePreview by remember(paneId) { mutableStateOf<String?>(null) }
    var terminalNotice by remember(paneId) { mutableStateOf<String?>(null) }
    var composeInsertion by remember(paneId) { mutableStateOf<TerminalDraftInsertion?>(null) }
    var insertionSequence by remember(paneId) { mutableLongStateOf(0L) }
    var viewportResetKey by remember(paneId) { mutableStateOf(0) }
    var selectionController by remember(paneId) { mutableStateOf<SelectionController?>(null) }
    val inputModes = remember(paneId) { TerminalInputModes() }
    val foreground = appearance.theme.foreground
    val emulator = remember(state.activeProfileId, paneId) {
        TerminalEmulatorFactory.create(
            initialRows = state.terminal.rows,
            initialCols = state.terminal.cols,
            defaultForeground = foreground,
            defaultBackground = appearance.theme.background,
            // Emulator-generated answers to terminal queries never become remote input.
            onKeyboardInput = {},
        )
    }
    var receivedFrame by remember(paneId) { mutableStateOf(false) }
    var sessionId by remember(paneId) { mutableLongStateOf(-1L) }
    // Codex 0.162 handles unmodified PageUp/PageDown in its owned transcript.
    // Ordinary shells keep finger gestures local until this is explicitly enabled.
    var scrollProgram by remember(state.activeProfileId, paneId, sessionId) { mutableStateOf(pane?.agent == "codex") }
    val frameRenderer = remember(emulator) {
        TerminalFrameRenderer(emulator.dimensions.rows, emulator.dimensions.columns,
            write = { emulator.writeInput(it) }, resize = { rows, cols -> emulator.resize(rows, cols) })
    }
    var inputController by remember(state.activeProfileId, paneId, sessionId) { mutableStateOf<TerminalInputController?>(null) }
    LaunchedEffect(paneId, emulator) {
        // Collection owns an observer subscription. Cancelling it closes the stream.
        viewModel.terminalFrames.collect { frame ->
            if (frame.paneId == paneId) {
                val reset = frame.reset || frame.sessionId != sessionId
                inputModes.observe(frame.ansi, reset = reset)
                frameRenderer.render(frame)
                sessionId = frame.sessionId
                receivedFrame = true
            }
        }
    }
    val canSend = state.terminal.canInput
    val renderedInputScope = viewModel.captureTerminalInputScope(state.activeProfileId, paneId, sessionId)
    val directInput = "terminal-input" in state.capabilities
    LaunchedEffect(canSend, directInput) {
        if (!canSend || !directInput) { keyboardVisible = false; ctrlPressed = false; altPressed = false }
    }
    val updateAppearance: (TerminalAppearance) -> Unit = { appearance = it; appearanceStore.write(it) }
    val requestPaste = {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        if (!text.isNullOrEmpty()) { keyboardVisible = false; ctrlPressed = false; altPressed = false; terminalNotice = null; pastePreview = text }
        else terminalNotice = "The clipboard contains no text."
    }
    val openPanel: (TerminalPanel) -> Unit = { keyboardVisible = false; panel = it }
    val onKey: (String) -> Unit = { key ->
        val modified = (if (ctrlPressed && !key.startsWith("ctrl+")) "ctrl+" else "") +
            (if (altPressed && !key.startsWith("alt+")) "alt+" else "") + key
        // The Herdr runtime knows the remote application's keyboard mode. Its
        // observer sends rendered cells, so local ANSI cannot infer that mode.
        val accepted = if (directInput) inputController?.sendKey(modified) ?: false
            else viewModel.sendKeys(renderedInputScope, listOf(modified))
        if (accepted) { ctrlPressed = false; altPressed = false }
    }
    BackHandler {
        when {
            pastePreview != null -> pastePreview = null
            panel != null -> panel = null
            keyboardVisible -> keyboardVisible = false
            focusMode -> focusMode = false
            else -> onBack()
        }
    }
    Scaffold(
        containerColor = appearance.theme.background,
        topBar = {
            if (!focusMode) TopAppBar(
                title = {
                    Column(Modifier.clickable { openPanel(TerminalPanel.PANES) }.testTag("terminal-session-switcher")) {
                        Text(pane?.title ?: "Terminal", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(workspace?.label, tab?.label).joinToString(" / ") + " ▾", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { androidx.compose.material3.IconButton(onClick = onBack) { SymbolIcon(Symbol.BACK, if (returnToChat) "Back to conversation" else "Back to workspaces") } },
                actions = {
                    if (returnToChat) TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 48.dp).testTag("return-to-chat")) { Text("Chat") }
                    androidx.compose.material3.IconButton(onClick = { openPanel(TerminalPanel.TOOLS) }, modifier = Modifier.testTag("terminal-tools")) { Text("⋮", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { contentDescription = "Terminal tools" }) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasColor),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            if (fallbackReason != null && !returnToChat && !focusMode) Text(fallbackReason, Modifier.padding(horizontal = 12.dp, vertical = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(Modifier.fillMaxWidth().background(CanvasColor).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
                if (focusMode) TextButton(onClick = { focusMode = false }, modifier = Modifier.testTag("terminal-leave-focus")) { Text("Exit focus") }
                if ("terminal-history" in state.capabilities) TextButton(onClick = { openPanel(TerminalPanel.OUTPUT) }, modifier = Modifier.testTag("terminal-history")) { Text("History") }
                if (directInput) TextButton(onClick = { scrollProgram = !scrollProgram; keyboardVisible = false; selectionController?.clearSelection() },
                    enabled = canSend, modifier = Modifier.testTag("terminal-scroll-program").semantics {
                        contentDescription = if (scrollProgram) "Stop program scroll" else "Scroll program"
                    }) { Text(if (scrollProgram) "Read" else "Scroll") }
                TextButton(onClick = { if (directInput) keyboardVisible = !keyboardVisible else openPanel(TerminalPanel.COMPOSE) }, enabled = canSend, modifier = Modifier.testTag("terminal-keyboard")) { Text(if (!directInput) "Compose" else if (keyboardVisible) "Hide keys" else "Keyboard") }
                if (focusMode) TextButton(onClick = { openPanel(TerminalPanel.TOOLS) }) { Text("Tools") }
            }
            if (scrollProgram) Text("Swipes send PgUp / PgDn to this program. Tap Read to stop.",
                Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag("terminal-scroll-program-hint"),
                style = MaterialTheme.typography.bodySmall, color = Mint)
            if (!directInput && !focusMode) Text("Update the host companion for direct keyboard input. Compose and special keys are available.", Modifier.padding(horizontal = 12.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = Amber)
            if (state.terminal.inputUncertain) Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Input delivery is uncertain. Review the terminal before continuing.", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
                TextButton(onClick = viewModel::acknowledgeTerminalInput, modifier = Modifier.testTag("terminal-resume-input")) { Text("Resume typing") }
            }
            if (state.error != null) ErrorNotice(state.error, viewModel::clearError, Modifier.padding(8.dp))
            terminalNotice?.let { notice -> Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(notice, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { terminalNotice = null }) { Text("Dismiss") }
            } }
            Box(Modifier.weight(1f).fillMaxWidth().background(appearance.theme.background)) {
                TerminalSurface(
                    emulator = emulator,
                    rows = state.terminal.rows,
                    cols = state.terminal.cols,
                    enabled = canSend && directInput,
                    showSoftKeyboard = keyboardVisible && panel == null && pastePreview == null,
                    fontSize = appearance.fontSize,
                    onFontSizeChange = { updateAppearance(appearance.copy(fontSize = it, fitToWidth = false)) },
                    foreground = foreground,
                    background = appearance.theme.background,
                    ctrlPressed = ctrlPressed,
                    altPressed = altPressed,
                    onModifierConsumed = { ctrlPressed = false; altPressed = false },
                    onInput = { bytes -> viewModel.sendTerminalInput(renderedInputScope, bytes) },
                    onKeyInput = { keys -> viewModel.sendKeys(renderedInputScope, keys) },
                    inputSessionKey = sessionId,
                    onInputControllerAvailable = { inputController = it },
                    onPasteRequest = requestPaste,
                    onPasteTextRequest = { text -> keyboardVisible = false; ctrlPressed = false; altPressed = false; pastePreview = text },
                    onKeyboardRequested = { if (canSend && directInput) keyboardVisible = true },
                    onImeVisibilityChanged = { keyboardVisible = it },
                    fitToWidth = appearance.fitToWidth,
                    viewportResetKey = viewportResetKey,
                    onSelectionControllerAvailable = { selectionController = it },
                    onHistoryRequested = if ("terminal-history" in state.capabilities) ({ openPanel(TerminalPanel.OUTPUT) }) else null,
                    scrollProgram = scrollProgram,
                    onProgramScroll = { older ->
                        // Herdr0.9.3 rejects its advertised logical pageup alias.
                        // The native raw-input queue preserves the standard key
                        // sequence and the same pane/session delivery guards.
                        viewModel.sendTerminalInput(renderedInputScope,
                            TerminalInputEncoder.key(if (older) TerminalInputEncoder.Key.PAGE_UP else TerminalInputEncoder.Key.PAGE_DOWN))
                    },
                    inputModes = inputModes,
                    modifier = Modifier.fillMaxSize().semantics { contentDescription = "Live terminal output for ${pane?.title ?: "selected pane"}" },
                )
                if (!receivedFrame) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (state.terminal.connecting) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(if (state.terminal.connecting) "Opening terminal…" else "Waiting for terminal output", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            CompactTerminalKeys(canSend, ctrlPressed, altPressed, { ctrlPressed = !ctrlPressed }, { altPressed = !altPressed }, onKey) { openPanel(TerminalPanel.KEYS) }
        }
    }
    when (panel) {
        TerminalPanel.TOOLS -> TerminalToolsSheet(
            onClose = { panel = null }, onCompose = { panel = TerminalPanel.COMPOSE }, onKeys = { panel = TerminalPanel.KEYS }, onAppearance = { panel = TerminalPanel.APPEARANCE },
            onPaste = { panel = null; requestPaste() }, onSelect = { panel = null; selectionController?.selectAllVisible() },
            onFocus = { focusMode = !focusMode; panel = null }, focusMode = focusMode, canPaste = canSend,
            onOutput = { panel = TerminalPanel.OUTPUT }, canReadOutput = "terminal-history" in state.capabilities,
        )
        TerminalPanel.OUTPUT -> key(state.activeProfileId, paneId, sessionId) {
            TerminalOutputSheet(paneId, load = { viewModel.readTerminalHistory() }, onClose = { panel = null },
                fullScreenHint = pane?.agent == "codex", onOpenKeys = { panel = TerminalPanel.KEYS })
        }
        TerminalPanel.KEYS -> TerminalKeysSheet(canSend, { panel = null }, onKey)
        TerminalPanel.APPEARANCE -> TerminalAppearanceSheet(appearance, updateAppearance, { viewportResetKey++; updateAppearance(TerminalAppearance(theme = appearance.theme)) }, { panel = null })
        TerminalPanel.PANES -> TerminalPaneSheet(state.snapshot?.panes.orEmpty(), paneId, { panel = null; keyboardVisible = false; viewModel.selectPane(it) }, { panel = null })
        TerminalPanel.COMPOSE -> {
            TerminalComposeSheet(
                paneId, canSend, state.sending, state.inputRevision, commandMemory,
                onMemoryChanged = { memoryRevision++; snippetStore.write(commandMemory.snippets) },
                onSend = { text, enter -> viewModel.sendText(renderedInputScope, text, enter) }, onClose = { panel = null; composeInsertion = null }, insertion = composeInsertion, draftState = composerDraft, memoryRevision = memoryRevision,
            )
        }
        null -> Unit
    }
    pastePreview?.let { text ->
        TerminalPasteReview(text, canSend, onInsert = { value ->
            // Preserve the remote application's bracketed-paste mode and do not
            // append Enter. Multiline text is reviewed in Compose instead.
            if (viewModel.sendText(renderedInputScope, value, false)) pastePreview = null
        }, onCompose = { value -> pastePreview = null; composeInsertion = TerminalDraftInsertion(++insertionSequence, value); panel = TerminalPanel.COMPOSE }, onClose = { pastePreview = null })
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
internal fun CommandComposer(
    paneId: String, enabled: Boolean, sending: Boolean, inputRevision: Long,
    draftInsertion: TerminalDraftInsertion? = null,
    draftState: TerminalDraftState? = null,
    onDraftChanged: (String) -> Unit = {},
    onAcknowledged: (String) -> Unit = {},
    onSend: (String, Boolean) -> Unit,
) {
    // Drafts, like credentials, stay in memory and are excluded from saved app state.
    val localDraftState = remember(paneId) { TerminalDraftState(inputRevision) }
    val rememberedDraft = draftState ?: localDraftState
    var draft by rememberedDraft.text
    var appendEnter by rememberedDraft.appendEnter
    var pendingDraft by rememberedDraft.pendingDraft
    var pendingRevision by rememberedDraft.pendingRevision
    LaunchedEffect(draftInsertion?.id) {
        draftInsertion?.let { draft = it.text; onDraftChanged(draft); pendingDraft = null }
    }
    LaunchedEffect(inputRevision) {
        if (inputRevision > pendingRevision && pendingDraft != null) {
            onAcknowledged(pendingDraft!!)
            if (draft == pendingDraft) { draft = ""; onDraftChanged(draft) }
            pendingDraft = null
        }
    }
    Column(Modifier.fillMaxWidth().background(CanvasColor).padding(horizontal = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it; onDraftChanged(it) },
                placeholder = { Text("Draft a command or prompt") },
                maxLines = 4,
                enabled = !sending,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, imeAction = ImeAction.Default),
                modifier = Modifier.weight(1f).testTag("command-draft"),
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            Button(
                enabled = enabled && !sending && draft.isNotEmpty(),
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
            TextButton(onClick = { draft = ""; onDraftChanged(draft); pendingDraft = null }, enabled = draft.isNotEmpty() && !sending) { Text("Clear") }
        }
    }
}
