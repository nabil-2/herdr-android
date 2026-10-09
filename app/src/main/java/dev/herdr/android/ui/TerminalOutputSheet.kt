package dev.herdr.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import dev.herdr.android.data.TerminalHistory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onStart
import kotlin.math.roundToInt

/** Read-only, bounded history. Output and searches never enter saved app state or preferences. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TerminalOutputSheet(
    paneId: String,
    load: suspend () -> TerminalHistory,
    onClose: () -> Unit,
    fullScreenHint: Boolean = false,
    onOpenKeys: (() -> Unit)? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentLoad by rememberUpdatedState(load)
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    var history by remember(paneId) { mutableStateOf<TerminalHistory?>(null) }
    var loading by remember(paneId) { mutableStateOf(false) }
    var error by remember(paneId) { mutableStateOf<String?>(null) }
    var query by remember(paneId) { mutableStateOf("") }
    var selectedMatch by remember(paneId) { mutableIntStateOf(0) }
    var copied by remember(paneId) { mutableStateOf(false) }
    var textLayout by remember(paneId) { mutableStateOf<TextLayoutResult?>(null) }
    var closed by remember(paneId) { mutableStateOf(false) }
    val refresh = remember(paneId) { MutableSharedFlow<Unit>(extraBufferCapacity = 1) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    val outputColor = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(paneId, lifecycle, closed) {
        if (closed) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            try {
                refresh.onStart { emit(Unit) }.collectLatest {
                    loading = true
                    error = null
                    copied = false
                    try {
                        val result = currentLoad()
                        currentCoroutineContext().ensureActive()
                        if (closed) throw CancellationException("Output history was closed.")
                        // Defensive even with the ViewModel's pane/connection gate.
                        val lines = result.text.count { it == '\n' } + if (result.text.isNotEmpty() && !result.text.endsWith('\n')) 1 else 0
                        require(result.paneId == paneId && result.text.toByteArray(Charsets.UTF_8).size <= 65536 && lines <= 1000)
                        history = result
                        selectedMatch = 0
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        history = null
                        error = "Could not load output. Check your connection and try again."
                    } finally {
                        loading = false
                    }
                }
            } finally {
                // Backgrounding cancels the read and clears the output immediately.
                history = null
                error = null
                query = ""
                textLayout = null
                copied = false
            }
        }
    }

    val text = history?.text.orEmpty()
    val matches = remember(text, query) {
        if (query.isEmpty()) emptyList() else buildList {
            var offset = 0
            while (offset <= text.length - query.length) {
                val next = text.indexOf(query, offset, ignoreCase = true)
                if (next < 0) break
                add(next)
                offset = next + query.length
            }
        }
    }
    val activeMatch = selectedMatch.coerceIn(0, (matches.size - 1).coerceAtLeast(0))
    val annotated = remember(text, query, matches, activeMatch, outputColor) {
        buildAnnotatedString {
            append(text)
            if (matches.isNotEmpty()) {
                val start = matches[activeMatch]
                addStyle(SpanStyle(background = Amber.copy(alpha = 0.35f), color = outputColor), start, start + query.length)
            }
        }
    }
    LaunchedEffect(query) { selectedMatch = 0 }
    LaunchedEffect(matches, activeMatch, textLayout) {
        val layout = textLayout ?: return@LaunchedEffect
        val offset = matches.getOrNull(activeMatch) ?: return@LaunchedEffect
        if (offset < layout.layoutInput.text.length) {
            val top = layout.getBoundingBox(offset).top - with(density) { 24.dp.toPx() }
            scroll.animateScrollTo(top.roundToInt().coerceAtLeast(0))
        }
    }
    val close = { closed = true; history = null; query = ""; textLayout = null; onClose() }
    ModalBottomSheet(onDismissRequest = close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize().padding(bottom = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Output history", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = close) { Text("Done") }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { refresh.tryEmit(Unit) }, enabled = !loading, modifier = Modifier.testTag("terminal-output-refresh")) { Text(if (error != null) "Retry" else "Refresh") }
                TextButton(onClick = { history?.let { clipboard.setText(AnnotatedString(it.text)); copied = true } }, enabled = history?.text?.isNotEmpty() == true && !loading, modifier = Modifier.testTag("terminal-output-copy")) { Text(if (copied) "Copied" else "Copy loaded output") }
                if (loading) CircularProgressIndicator(Modifier.padding(start = 8.dp).size(18.dp), strokeWidth = 2.dp)
            }
            Text(if (history?.truncated == true) "Recent output; older text omitted" else "A snapshot of recent output. Refresh to see new text.", Modifier.padding(horizontal = 20.dp, vertical = 4.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (fullScreenHint) {
                Text("Full-screen apps may expose only their current screen. To scroll inside the app, use PgUp / PgDn in Keys.",
                    Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("terminal-output-fullscreen-hint"),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onOpenKeys != null) TextButton(onClick = { close(); onOpenKeys() },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("terminal-output-program-keys")) { Text("Open Keys") }
            }
            OutlinedTextField(value = query, onValueChange = { query = it.take(128) }, label = { Text("Find in loaded output") }, singleLine = true, enabled = history != null && !loading, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag("terminal-output-find"))
            if (query.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (matches.isEmpty()) "No matches" else "${activeMatch + 1} of ${matches.size}", Modifier.weight(1f).testTag("terminal-output-match-count"), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = { selectedMatch = (activeMatch - 1 + matches.size) % matches.size }, enabled = matches.isNotEmpty(), modifier = Modifier.testTag("terminal-output-previous")) { Text("Previous") }
                TextButton(onClick = { selectedMatch = (activeMatch + 1) % matches.size }, enabled = matches.isNotEmpty(), modifier = Modifier.testTag("terminal-output-next")) { Text("Next") }
            }
            Box(Modifier.weight(1f).fillMaxWidth().background(TerminalColor)) {
                when {
                    error != null -> Text(error!!, Modifier.align(Alignment.Center).padding(24.dp).testTag("terminal-output-error"), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    history == null -> Text(if (loading) "Loading recent output…" else "No output loaded", Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    text.isEmpty() -> Text("No terminal output yet", Modifier.align(Alignment.Center).padding(24.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    else -> SelectionContainer {
                        Text(annotated, Modifier.fillMaxSize().verticalScroll(scroll).padding(16.dp).testTag("terminal-output-text"), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface, onTextLayout = { textLayout = it })
                    }
                }
            }
        }
    }
}
