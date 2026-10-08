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
)

@Serializable
data class Snapshot(
    val herdrVersion: String = "",
    val workspaces: List<Workspace> = emptyList(),
    val tabs: List<Tab> = emptyList(),
    val panes: List<Pane> = emptyList(),
)

@Serializable
data class Health(val service: String, val protocol: Int, val herdrVersion: String = "")

/** Public profile metadata deliberately excludes credentials. */
data class ServerProfile(val id: String, val name: String, val baseUrl: String, val hasToken: Boolean = true)

@Serializable
internal data class PaneInput(val text: String? = null, val keys: List<String> = emptyList())

@Serializable
internal data class WorkspaceInput(val cwd: String, val label: String)

@Serializable
internal data class CreatedWorkspace(val id: String)

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, ERROR }

data class TerminalContent(
    val cols: Int = 80,
    val rows: Int = 30,
    val connected: Boolean = false,
    val connecting: Boolean = false,
    val revision: Long = 0,
)

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
    val terminal: TerminalContent = TerminalContent(),
    val error: String? = null,
    val refreshing: Boolean = false,
    val sending: Boolean = false,
    val creatingWorkspace: Boolean = false,
    val inputRevision: Long = 0,
    val pairing: Boolean = false,
    val pairingSuccessRevision: Long = 0,
)
