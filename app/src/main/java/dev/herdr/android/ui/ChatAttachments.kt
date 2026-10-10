package dev.herdr.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.content.ReceiveContentListener
import androidx.compose.foundation.content.TransferableContent
import androidx.compose.foundation.content.consume
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.herdr.android.data.ChatAttachment
import dev.herdr.android.data.ChatAttachmentKind
import dev.herdr.android.data.ChatAttachmentStatus
import dev.herdr.android.data.ChatAttachmentTarget
import dev.herdr.android.data.ConnectionState
import dev.herdr.android.data.HerdrUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The picker target is captured before leaving the activity, never on its return. */
@Composable
internal fun ChatAttachments(
    state: HerdrUiState,
    onAdd: (ChatAttachmentTarget, List<Uri>, ChatAttachmentKind) -> Unit,
    onRemove: (ChatAttachmentTarget, String) -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val target = state.chatAttachmentTarget()
    var pendingImages by remember { mutableStateOf<ChatAttachmentTarget?>(null) }
    var pendingFiles by remember { mutableStateOf<ChatAttachmentTarget?>(null) }
    var clipboardError by remember(target) { mutableStateOf<String?>(null) }
    val images = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingImages?.let { captured -> if (uris.isNotEmpty()) onAdd(captured, uris, ChatAttachmentKind.IMAGE) }
        pendingImages = null
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pendingFiles?.let { captured -> if (uris.isNotEmpty()) onAdd(captured, uris, ChatAttachmentKind.FILE) }
        pendingFiles = null
    }
    val enabled = target != null && state.connection == ConnectionState.CONNECTED &&
        !state.chat.sending && !state.chat.deliveryUncertain && !state.chat.conversationChanged && "codex-chat-attachments" in state.capabilities
    Column(modifier.testTag("chat-attachments")) {
        if (state.chat.attachments.isNotEmpty()) {
            Row(Modifier.horizontalScroll(rememberScrollState()).heightIn(max = 220.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.chat.attachments.forEach { attachment ->
                    AttachmentPreview(attachment, !state.chat.sending) { target?.let { onRemove(it, attachment.localId) } }
                }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            TextButton(enabled = enabled, modifier = Modifier.testTag("attach-image"), onClick = {
                pendingImages = target
                images.launch(arrayOf("image/png", "image/jpeg", "image/webp"))
            }) { Text("Images") }
            TextButton(enabled = enabled, modifier = Modifier.testTag("attach-file"), onClick = {
                pendingFiles = target
                files.launch(arrayOf("*/*"))
            }) { Text("Files") }
            TextButton(enabled = enabled, modifier = Modifier.testTag("paste-chat-image"), onClick = {
                // Clipboard access happens only on this explicit user action.
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = clipboard.primaryClip
                val uris = (0 until (clip?.itemCount ?: 0)).mapNotNull { clip?.getItemAt(it)?.uri }
                if (uris.isEmpty()) clipboardError = "Copy an image first, or select one with Images."
                else target?.let { onAdd(it, uris, ChatAttachmentKind.IMAGE); clipboardError = null }
            }) { Text("Paste image") }
        }
        if ("codex-chat-attachments" !in state.capabilities) {
            Text("Update the host companion to attach images and files.", style = MaterialTheme.typography.bodySmall)
        }
        val error = state.chat.attachmentError ?: clipboardError
        if (error != null) {
            Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("chat-attachment-error"))
            TextButton(onClick = { clipboardError = null; onDismissError() }) { Text("Dismiss") }
        }
    }
}

@Composable
private fun AttachmentPreview(attachment: ChatAttachment, removable: Boolean, onRemove: () -> Unit) {
    val image by produceState<ImageBitmap?>(null, attachment.previewPath) {
        value = withContext(Dispatchers.IO) { attachment.previewPath?.let { runCatching { BitmapFactory.decodeFile(it)?.asImageBitmap() }.getOrNull() } }
    }
    Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.widthIn(max = 220.dp).testTag("chat-attachment-${attachment.localId}")) {
        Column(Modifier.padding(8.dp)) {
            image?.let { Image(it, "Attached image: ${attachment.name}", Modifier.size(96.dp), contentScale = ContentScale.Fit) }
            Text(attachment.name, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            when (attachment.status) {
                ChatAttachmentStatus.PREPARING, ChatAttachmentStatus.UPLOADING -> {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text(if (attachment.status == ChatAttachmentStatus.PREPARING) "Preparing…" else "Uploading…", style = MaterialTheme.typography.labelSmall)
                }
                ChatAttachmentStatus.READY -> Text("Ready · ${if (attachment.sizeBytes < 1024) "${attachment.sizeBytes} bytes" else "${attachment.sizeBytes / 1024} KiB"}", style = MaterialTheme.typography.labelSmall)
                ChatAttachmentStatus.FAILED -> Text(attachment.error ?: "Remove and attach again.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, maxLines = 3)
                ChatAttachmentStatus.DELIVERY_UNCERTAIN -> Text("Check Terminal before attaching again.", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(enabled = removable, modifier = Modifier.testTag("remove-chat-attachment-${attachment.localId}"), onClick = onRemove) { Text("Remove") }
        }
    }
}

internal fun HerdrUiState.chatAttachmentTarget(): ChatAttachmentTarget? {
    return ChatAttachmentTarget(activeProfileId ?: return null, paneId ?: return null, chat.bindingId ?: return null)
}

/** Compose forwards clipboard, drag/drop and supported keyboard image commits here. Text stays with the editor. */
@OptIn(ExperimentalFoundationApi::class)
internal fun chatImageContentReceiver(
    target: ChatAttachmentTarget?,
    onAdd: (ChatAttachmentTarget, List<Uri>, ChatAttachmentKind) -> Unit,
    enabled: Boolean = true,
): Modifier = Modifier.contentReceiver(object : ReceiveContentListener {
    override fun onReceive(transferableContent: TransferableContent): TransferableContent? {
        if (!enabled || target == null) return transferableContent
        val images = mutableListOf<Uri>()
        val remainder = transferableContent.consume { item ->
            val uri = item.uri
            val isImage = transferableContent.clipEntry.clipData.description.hasMimeType("image/*")
            if (uri != null && uri.scheme == "content" && isImage) { images += uri; true } else false
        }
        if (images.isNotEmpty()) onAdd(target, images, ChatAttachmentKind.IMAGE)
        return remainder
    }
})
