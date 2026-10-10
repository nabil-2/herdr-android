package dev.herdr.android.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.android.data.CodexConversationSelectionState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
internal fun CodexConversationSelector(
    selection: CodexConversationSelectionState,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onCancel: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.padding(20.dp).testTag("codex-conversation-selector"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Choose a conversation", style = MaterialTheme.typography.titleLarge)
        Text("Choose the Codex conversation open in this tab. Your messages will go to that conversation.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (selection.loading || selection.selecting) CircularProgressIndicator(Modifier.testTag("codex-selection-loading"))
        selection.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("codex-selection-error")) }
        if (!selection.loading && selection.choices.isEmpty() && selection.error == null)
            Text("No running Codex conversations were found. Try Refresh or open Terminal.", style = MaterialTheme.typography.bodyMedium)
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(selection.choices, key = { it.id }) { choice ->
                Surface(
                    onClick = { onSelect(choice.id) },
                    enabled = !selection.loading && !selection.selecting,
                    shape = MaterialTheme.shapes.medium,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("codex-conversation-${choice.id}"),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(choice.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        val date = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).format(Instant.ofEpochSecond(choice.createdAt).atZone(ZoneId.systemDefault()))
                        Text("$date · ${choice.status.replaceFirstChar { it.uppercase() }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(choice.id.take(8), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onRefresh, enabled = !selection.loading && !selection.selecting, modifier = Modifier.testTag("refresh-codex-conversations")) { Text("Refresh") }
            if (onCancel != null) TextButton(onClick = onCancel, enabled = !selection.selecting) { Text("Back to chat") }
        }
    }
}
