package dev.herdr.android.data

import kotlinx.serialization.Serializable

@Serializable
data class Workspace(val id: String, val label: String, val cwd: String)

@Serializable
data class Tab(val id: String, val workspaceId: String, val label: String)

@Serializable
data class Pane(
    val id: String,
    val workspaceId: String,
    val tabId: String,
    val title: String,
    val cwd: String,
    val agent: String? = null,
    val status: String = "unknown",
    val conversation: ConversationAvailability? = null,
)

@Serializable
data class ConversationAvailability(val provider: String, val available: Boolean, val reason: String? = null, val selectable: Boolean = false)

@Serializable
data class Snapshot(
    val herdrVersion: String = "",
    val workspaces: List<Workspace> = emptyList(),
    val tabs: List<Tab> = emptyList(),
    val panes: List<Pane> = emptyList(),
)

@Serializable
data class Health(
    val service: String,
    val protocol: Int,
    val herdrVersion: String = "",
    val capabilities: List<String> = emptyList(),
)

enum class PaneMode { CHAT, TERMINAL }

@Serializable
data class ChatMessage(
    val id: String,
    val role: String,
    val text: String,
    val phase: String? = null,
    val timestamp: Long? = null,
    val timestampSource: String? = null,
    val messageTimestamp: Long? = null,
)

@Serializable
internal data class ConversationPage(
    val binding: String,
    val revision: String,
    val historyEpoch: String,
    val messages: List<ChatMessage>,
    val olderCursor: String? = null,
    val unchanged: Boolean,
    val canSend: Boolean,
    val status: String,
    val freshBootstrap: Boolean = false,
    val warning: String? = null,
    val canSendReason: String? = null,
    val attachmentDeliveryUncertain: Boolean = false,
    val conversationId: String? = null,
)

@Serializable
internal data class ChatInput(val binding: String, val text: String, val attachmentIds: List<String> = emptyList())

enum class ChatAttachmentKind(val wireName: String) { IMAGE("image"), FILE("file") }
enum class ChatAttachmentStatus { PREPARING, UPLOADING, READY, FAILED, DELIVERY_UNCERTAIN }
data class ChatAttachmentTarget(val profileId: String, val paneId: String, val binding: String)
data class ChatAttachment(
    val localId: String,
    val name: String,
    val kind: ChatAttachmentKind,
    val sizeBytes: Long = 0,
    val mimeType: String? = null,
    val remoteId: String? = null,
    val previewPath: String? = null,
    val status: ChatAttachmentStatus = ChatAttachmentStatus.PREPARING,
    val error: String? = null,
) {
    override fun toString(): String = "ChatAttachment(kind=$kind, status=$status, redacted)"
}

@Serializable
internal data class UploadedChatAttachment(val id: String, val name: String, val kind: String, val mimeType: String, val sizeBytes: Long)

data class ChatContent(
    val bindingId: String? = null,
    val revision: String? = null,
    val historyEpoch: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val olderCursor: String? = null,
    val loading: Boolean = false,
    val refreshing: Boolean = false,
    val loadingOlder: Boolean = false,
    val canSend: Boolean = false,
    val canSendReason: String? = null,
    val status: String = "unknown",
    val freshBootstrap: Boolean = false,
    val warning: String? = null,
    val error: String? = null,
    val sending: Boolean = false,
    val draft: String = "",
    val ackRevision: Long = 0,
    val deliveryUncertain: Boolean = false,
    val attachmentDeliveryUncertain: Boolean = false,
    val attachments: List<ChatAttachment> = emptyList(),
    val attachmentError: String? = null,
    val conversationId: String? = null,
    val conversationChanged: Boolean = false,
    val readRetrying: Boolean = false,
) {
    /** Herdr accepts follow-ups while Codex is working; server readiness stays authoritative. */
    fun canSubmitMessage(): Boolean = canSend && (status in setOf("idle", "done", "working") || (status == "unknown" && freshBootstrap)) &&
        bindingId != null && error == null && !loading && !readRetrying && !sending && !deliveryUncertain && !attachmentDeliveryUncertain && !conversationChanged &&
        attachments.all { it.status == ChatAttachmentStatus.READY && it.remoteId != null }

    override fun toString(): String = "ChatContent(messages=${messages.size}, loading=$loading, sending=$sending)"
}

/** Public profile metadata deliberately excludes credentials. */
data class ServerProfile(val id: String, val name: String, val baseUrl: String, val hasToken: Boolean = true)

@Serializable
internal data class PaneInput(val text: String? = null, val keys: List<String> = emptyList())

@Serializable
internal data class RawTerminalInput(val text: String) {
    override fun toString(): String = "RawTerminalInput(redacted)"
}

@Serializable
internal data class WorkspaceInput(val cwd: String, val label: String)

@Serializable
internal data class CreatedWorkspace(val id: String)

@Serializable
internal data class TabInput(val kind: String, val label: String, val cwd: String? = null)

@Serializable
internal data class CreatedTab(val workspaceId: String, val tabId: String, val paneId: String, val warning: String? = null)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class TerminalContent(
    val cols: Int = 80,
    val rows: Int = 30,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val revision: Long = 0,
    val canInput: Boolean = false,
    val inputUncertain: Boolean = false,
)

data class TerminalHistory(val paneId: String, val text: String, val truncated: Boolean) {
    override fun toString(): String = "TerminalHistory(truncated=$truncated, redacted)"
}

data class TerminalFrame(
    val ansi: ByteArray,
    val cols: Int,
    val rows: Int,
    val reset: Boolean = false,
    val sessionId: Long = 0,
    val paneId: String = "",
)

data class HerdrUiState(
    val profiles: List<ServerProfile> = emptyList(),
    val activeProfileId: String? = null,
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val snapshot: Snapshot? = null,
    val workspaceId: String? = null,
    val tabId: String? = null,
    val paneId: String? = null,
    val capabilities: List<String> = emptyList(),
    val paneMode: PaneMode = PaneMode.TERMINAL,
    val chat: ChatContent = ChatContent(),
    val terminal: TerminalContent = TerminalContent(),
    val error: String? = null,
    val refreshing: Boolean = false,
    val sending: Boolean = false,
    val creatingWorkspace: Boolean = false,
    val creatingTab: Boolean = false,
    val creationUncertain: Boolean = false,
    val creationRevision: Long = 0,
    val inputRevision: Long = 0,
    val pairing: Boolean = false,
    val pairingSuccessRevision: Long = 0,
    val codexSelection: CodexConversationSelectionState = CodexConversationSelectionState(),
)
