package dev.herdr.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.markdownPadding
import com.mikepenz.markdown.model.rememberMarkdownState
import dev.herdr.android.data.ChatContent
import dev.herdr.android.data.ChatMessage
import dev.herdr.android.data.ConnectionState
import dev.herdr.android.data.HerdrUiState
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChatScreen(
    state: HerdrUiState,
    onBack: () -> Unit,
    onTerminal: () -> Unit,
    onRetry: () -> Unit,
    onOlder: () -> Unit,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onAcknowledgeDelivery: () -> Unit,
) {
    val pane = state.snapshot?.panes?.firstOrNull { it.id == state.paneId }
    val workspace = state.snapshot?.workspaces?.firstOrNull { it.id == pane?.workspaceId }
    val tab = state.snapshot?.tabs?.firstOrNull { it.id == pane?.tabId }
    val chat = state.chat
    val connected = state.connection == ConnectionState.CONNECTED
    BackHandler(onBack = onBack)
    Scaffold(
        containerColor = CanvasColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(pane?.title ?: "Codex", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(workspace?.label, tab?.label).joinToString(" / "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { SymbolIcon(Symbol.BACK, "Back to workspaces") } },
                actions = {
                    TextButton(onClick = onTerminal, modifier = Modifier.heightIn(min = 48.dp).testTag("open-chat-terminal")) { Text("Terminal") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasColor),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding().testTag("chat-screen")) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            when {
                !connected -> ChatNotice("Server disconnected", "Reconnect to update this conversation and send messages.", "Reconnect", onRetry)
                chat.status == "blocked" -> ChatNotice("Codex needs your attention", "Open the terminal to answer a question or approve an action.", "Open terminal", onTerminal, Modifier.testTag("chat-attention"))
            }
            if (chat.deliveryUncertain) ChatNotice("Message delivery is uncertain", "Check the conversation or terminal before sending this message again.", "I checked the conversation", onAcknowledgeDelivery, Modifier.testTag("chat-delivery-uncertain"))
            else if (chat.error != null) ChatNotice("Conversation needs a refresh", chat.error, "Refresh conversation", onRetry)
            if (chat.warning != null) Text(chat.warning, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = Amber, style = MaterialTheme.typography.bodySmall)
            key(chat.bindingId, chat.historyEpoch) {
                ConversationMessages(chat, onOlder, Modifier.weight(1f).fillMaxWidth())
            }
            if (connected && chat.status == "working") {
                Text("Working…", Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp).testTag("chat-working"), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

            }
            ChatComposer(
                draft = chat.draft,
                canSend = connected && chat.canSend && chat.status in setOf("idle", "done") && chat.bindingId != null && chat.error == null && !chat.loading && !chat.sending && !chat.deliveryUncertain,
                sending = chat.sending,
                editable = chat.bindingId != null,
                disabledReason = chat.canSendReason?.takeIf { connected && !chat.canSend && chat.status !in setOf("working", "blocked") && chat.error == null && !chat.loading && !chat.sending },
                onDraftChange = onDraftChange,
                onSend = onSend,
            )
        }
    }
}

@Composable
private fun ChatNotice(title: String, detail: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) { Text(action) }
        }
    }
}

@Composable
internal fun ConversationMessages(chat: ChatContent, onOlder: () -> Unit, modifier: Modifier = Modifier) {
    // Filter defensively even though the bridge/client accept only these two roles.
    val messages = chat.messages.filter { it.role == "user" || it.role == "assistant" }
    val last = messages.lastOrNull()
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var followTail by rememberSaveable(chat.bindingId) { mutableStateOf(true) }
    var scrollingToTail by remember { mutableStateOf(false) }
    var tailHeight by remember(last?.id) { mutableIntStateOf(0) }
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    LaunchedEffect(list) {
        var previous: ChatViewport? = null
        snapshotFlow {
            ChatViewport(list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset, list.layoutInfo.totalItemsCount,
                list.isScrollInProgress, !list.canScrollForward)
        }.collect { viewport ->
            val old = previous
            val moved = old != null && old.count == viewport.count && (old.index != viewport.index || old.offset != viewport.offset)
            if (!scrollingToTail && (viewport.scrolling || moved)) followTail = viewport.bottom
            previous = viewport
        }
    }
    LaunchedEffect(chat.bindingId, last?.id, last?.text, tailHeight) {
        if (last != null && !chat.loadingOlder) {
            withFrameNanos { }
            if (!followTail) return@LaunchedEffect
            scrollingToTail = true
            try { scrollToConversationEnd(list) }
            finally { scrollingToTail = false }
        }
    }
    Box(modifier) {
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().testTag("chat-messages"),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "older-messages") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (chat.olderCursor != null || chat.loadingOlder) TextButton(onClick = { followTail = false; onOlder() }, enabled = !chat.loadingOlder, modifier = Modifier.heightIn(min = 48.dp).testTag("load-older-messages")) {
                        if (chat.loadingOlder) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text("Load earlier messages")
                    }
                }
            }
            if (messages.isEmpty()) item(key = "empty-conversation") {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (chat.loading) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    Text(if (chat.loading) "Opening conversation…" else if (chat.bindingId == null) "Conversation unavailable" else "Start a conversation", style = MaterialTheme.typography.titleMedium)
                    if (!chat.loading) Text(if (chat.bindingId == null) "Use the terminal to control this session." else "Write to the Codex session running on your computer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            itemsIndexed(messages, key = { _, message -> message.id }, contentType = { _, message -> message.role }) { index, message ->
                Column(if (message.id == last?.id) Modifier.onSizeChanged { tailHeight = it.height } else Modifier) {
                    val date = chatMessageDate(message.timestamp)
                    if (date != null && date != chatMessageDate(messages.getOrNull(index - 1)?.timestamp)) {
                        ChatDateDivider(date)
                    }
                    ChatMessageCard(message)
                }
            }
        }
        if (!atBottom && messages.isNotEmpty()) Surface(shape = MaterialTheme.shapes.large, tonalElevation = 4.dp, modifier = Modifier.align(Alignment.BottomCenter).padding(8.dp)) {
            TextButton(onClick = {
                followTail = true
                scope.launch {
                    scrollingToTail = true
                    try { scrollToConversationEnd(list) }
                    finally { scrollingToTail = false }
                }
            }, modifier = Modifier.heightIn(min = 48.dp).testTag("latest-messages")) { Text("Latest messages") }
        }
    }
}

private data class ChatViewport(val index: Int, val offset: Int, val count: Int, val scrolling: Boolean, val bottom: Boolean)

private suspend fun scrollToConversationEnd(list: LazyListState) {
    val lastIndex = list.layoutInfo.totalItemsCount - 1
    if (lastIndex < 0) return
    list.scrollToItem(lastIndex)
    val tail = list.layoutInfo.visibleItemsInfo.lastOrNull() ?: return
    val remaining = tail.offset + tail.size - list.layoutInfo.viewportEndOffset + list.layoutInfo.afterContentPadding
    if (remaining > 0) list.scrollBy(remaining.toFloat())
}

/** Unknown timestamps stay unknown; the phone never assigns a date to old transcript text. */
internal fun chatMessageDate(timestamp: Long?, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
    timestamp?.takeIf { it > 0 }?.let { runCatching { Instant.ofEpochSecond(it).atZone(zone).toLocalDate() }.getOrNull() }

internal fun chatDateLabel(date: LocalDate, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date)
}

@Composable
private fun ChatDateDivider(date: LocalDate) {
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp).testTag("chat-date-$date"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        Text(chatDateLabel(date, locale = locale), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    }
}

@Composable
internal fun ChatMessageCard(message: ChatMessage, modifier: Modifier = Modifier) {
    if (message.role !in setOf("user", "assistant")) return
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    val user = message.role == "user"
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val time = remember(message.timestamp, configuration) {
        message.timestamp?.takeIf { chatMessageDate(it) != null && it <= Long.MAX_VALUE / 1000 }?.let {
            android.text.format.DateFormat.getTimeFormat(context).format(Date(it * 1000))
        }
    }
    val timeCaption = time?.let { when (message.timestampSource) {
        "turn_start" -> "Turn started $it"
        "turn_end" -> "Turn finished $it"
        else -> it
    } }
    Column(modifier.fillMaxWidth().testTag("chat-message-${message.id}"), horizontalAlignment = if (user) Alignment.End else Alignment.Start) {
        if (user) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.75f), shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp), modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(0.9f)) {
                SelectionContainer {
                    Text(message.text, style = chatBodyStyle(), modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!user) Text("Codex", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = Mint)
            if (timeCaption != null) Text(timeCaption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = if (user) TextAlign.End else TextAlign.Start, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).testTag("chat-time-${message.id}"))
            else Spacer(Modifier.weight(1f))
            if (user) Text("You", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }, modifier = Modifier.size(48.dp).semantics { contentDescription = "Copy ${if (user) "your" else "Codex"} message" }) {
                ChatCopyGlyph()
            }
        }
        if (!user) SelectionContainer { AssistantMarkdown(message.text) }
    }
}

@Composable
private fun ChatCopyGlyph() {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(Modifier.size(18.dp)) {
        val unit = size.width / 18f
        drawRoundRect(color, Offset(2f * unit, 2f * unit), Size(9f * unit, 11f * unit), CornerRadius(1.5f * unit), style = Stroke(1.3f * unit))
        drawRoundRect(color, Offset(6f * unit, 5f * unit), Size(10f * unit, 11f * unit), CornerRadius(1.5f * unit), style = Stroke(1.3f * unit))
    }
}

@Composable
private fun chatBodyStyle() = MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp)

/** Explicit clicks can open web links; transcript content cannot launch other schemes. */
internal fun safeChatLink(value: String): String? {
    if (value.any { it.code < 32 || it.code == 127 }) return null
    val url = value.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty()) return null
    return url.toString()
}

@Composable
internal fun AssistantMarkdown(text: String) {
    val originalHandler = LocalUriHandler.current
    val safeHandler = remember(originalHandler) {
        object : UriHandler {
            override fun openUri(uri: String) {
                safeChatLink(uri)?.let { safe -> runCatching { originalHandler.openUri(safe) } }
            }
        }
    }
    val parsed = rememberMarkdownState(content = text, retainState = true)
    CompositionLocalProvider(LocalUriHandler provides safeHandler) {
        Markdown(
            markdownState = parsed,
            typography = markdownTypography(
                h1 = chatBodyStyle().copy(fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
                h2 = chatBodyStyle().copy(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
                h3 = chatBodyStyle().copy(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
                h4 = chatBodyStyle().copy(fontWeight = FontWeight.SemiBold),
                h5 = chatBodyStyle().copy(fontWeight = FontWeight.SemiBold),
                h6 = chatBodyStyle().copy(fontWeight = FontWeight.SemiBold),
                text = chatBodyStyle(), paragraph = chatBodyStyle(), ordered = chatBodyStyle(),
                bullet = chatBodyStyle(), list = chatBodyStyle(), table = chatBodyStyle(),
                code = chatBodyStyle().copy(fontSize = 14.sp, lineHeight = 21.sp, fontFamily = FontFamily.Monospace),
                inlineCode = chatBodyStyle().copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                quote = chatBodyStyle().copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            ),
            padding = markdownPadding(block = 6.dp, list = 4.dp, listItemTop = 2.dp, listItemBottom = 2.dp, listIndent = 12.dp, codeBlock = PaddingValues(0.dp)),
            modifier = Modifier.fillMaxWidth(),
            components = markdownComponents(
                codeFence = { model -> MarkdownCodeFence(model.content, model.node, model.typography.code) { code, language, _ -> ChatCodeBlock(code, language) } },
                codeBlock = { model -> MarkdownCodeBlock(model.content, model.node, model.typography.code) { code, language, _ -> ChatCodeBlock(code, language) } },
                image = { Text("Image attachment", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                inlineImage = { Text("Image attachment", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                table = { model ->
                    MarkdownTable(model.content, model.node, model.typography.table,
                        headerBlock = { content, node, width, style -> MarkdownTableHeader(content, node, width, style, maxLines = Int.MAX_VALUE) },
                        rowBlock = { content, node, width, style -> MarkdownTableRow(content, node, width, style, maxLines = Int.MAX_VALUE) },
                    )
                },
            ),
            loading = { Text(text, style = chatBodyStyle()) },
            error = { Text(text, style = chatBodyStyle()) },
        )
    }
}

@Composable
private fun ChatCodeBlock(code: String, language: String?) {
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    Surface(color = TerminalColor, shape = MaterialTheme.shapes.small, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language?.takeIf { it.isNotBlank() } ?: "Code", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                TextButton(onClick = { clipboard.setText(AnnotatedString(code)) }, modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Copy code" }) { Text("Copy code", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            Text(code, Modifier.horizontalScroll(rememberScrollState()).padding(start = 12.dp, end = 12.dp, bottom = 12.dp), style = chatBodyStyle().copy(fontSize = 14.sp, lineHeight = 21.sp), fontFamily = FontFamily.Monospace)
        }
    }
}

@Composable
internal fun ChatComposer(draft: String, canSend: Boolean, sending: Boolean, editable: Boolean = true, disabledReason: String? = null, onDraftChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(color = CanvasColor) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = draft, onValueChange = onDraftChange,
                    enabled = editable,
                    placeholder = { Text("Message Codex", style = chatBodyStyle(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    minLines = 1, maxLines = 5,
                    shape = RoundedCornerShape(22.dp),
                    colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant, focusedBorderColor = MaterialTheme.colorScheme.primary, unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
                    modifier = Modifier.weight(1f).testTag("chat-draft"),
                    textStyle = chatBodyStyle(),
                )
                FilledIconButton(onClick = onSend, enabled = canSend && draft.isNotBlank() && !sending, shape = RoundedCornerShape(16.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = Mint, contentColor = MaterialTheme.colorScheme.onPrimary), modifier = Modifier.size(48.dp).testTag("send-chat").semantics { contentDescription = if (sending) "Sending message" else "Send message" }) {
                    if (sending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else SymbolIcon(Symbol.SEND)
                }
            }
            if (!canSend && disabledReason != null) Text(disabledReason, Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 10.dp).testTag("chat-send-reason"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
