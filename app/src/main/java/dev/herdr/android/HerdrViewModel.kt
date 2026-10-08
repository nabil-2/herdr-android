package dev.herdr.android

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.herdr.android.data.BridgeException
import dev.herdr.android.data.ConnectionState
import dev.herdr.android.data.HerdrClient
import dev.herdr.android.data.HerdrUiState
import dev.herdr.android.data.ProfileStore
import dev.herdr.android.data.PairingClient
import dev.herdr.android.data.PairingException
import dev.herdr.android.data.PairingPayload
import dev.herdr.android.data.Snapshot
import dev.herdr.android.data.TerminalContent
import dev.herdr.android.data.TerminalEndedException
import dev.herdr.android.data.TerminalFrame
import dev.herdr.android.data.TerminalMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class HerdrViewModel(application: Application) : AndroidViewModel(application) {
    private val profileStore = ProfileStore(application)
    private val mutableState = MutableStateFlow(HerdrUiState())
    val state: StateFlow<HerdrUiState> = mutableState.asStateFlow()
    // An observer is opened only after its screen collects frames. This guarantees the
    // collector receives its initial full frame before any incremental ANSI frames.
    private val mutableTerminalFrames = MutableSharedFlow<TerminalFrame>()
    val terminalFrames: SharedFlow<TerminalFrame> = mutableTerminalFrames.asSharedFlow()
    private var client: HerdrClient? = null
    private var generation = 0L
    private var terminalSession = 0L
    private var refreshGeneration = 0L
    private var foreground = false
    private var connectJob: Job? = null
    private var refreshJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var terminalJob: Job? = null
    private var inputJob: Job? = null
    private var createJob: Job? = null
    private var pairingJob: Job? = null
    private var pairingClient: PairingClient? = null

    init {
        reloadProfiles()
        viewModelScope.launch {
            mutableTerminalFrames.subscriptionCount.collect {
                restartObservation()
            }
        }
    }

    fun saveProfile(name: String, baseUrl: String, token: String, id: String? = null): Boolean {
        return try {
            profileStore.save(name, baseUrl, token, id)
            reloadProfiles()
            clearError()
            true
        } catch (error: Exception) {
            showError(if (error is IllegalArgumentException) error.message ?: "Check the server details." else "Could not securely save this profile.")
            false
        }
    }

    fun removeProfile(id: String) {
        try {
            profileStore.remove(id)
            if (state.value.activeProfileId == id) disconnect()
            reloadProfiles()
        } catch (_: Exception) { showError("Could not remove this profile.") }
    }

    fun pairServer(payload: String) {
        if (state.value.pairing || !ensureNetworkPermission()) return
        val invitation = try { PairingPayload.parse(payload) }
        catch (error: PairingException) { showError(error.message ?: "Generate a new pairing code on your server."); return }
        releaseConnection()
        val currentGeneration = generation
        val profileId = UUID.randomUUID().toString()
        val newClient = PairingClient()
        pairingClient = newClient
        mutableState.update {
            HerdrUiState(profiles = it.profiles, pairing = true, pairingSuccessRevision = it.pairingSuccessRevision)
        }
        pairingJob = viewModelScope.launch {
            var stored = false
            try {
                val deviceName = listOf(Build.MANUFACTURER, Build.MODEL).joinToString(" ")
                    .filter { it.code >= 32 && it.code != 127 }.take(100).ifBlank { "Android phone" }
                val result = newClient.pair(invitation, deviceName)
                if (generation != currentGeneration) return@launch
                withContext(Dispatchers.IO) {
                    profileStore.save(result.serverName, invitation.url, result.token, profileId)
                    stored = true
                }
                if (generation != currentGeneration) return@launch
                reloadProfiles()
                // Keep the monotonic success signal when connect replaces UI state.
                mutableState.update {
                    it.copy(pairing = false, pairingSuccessRevision = it.pairingSuccessRevision + 1, error = null)
                }
                pairingJob = null
                pairingClient = null
                newClient.close()
                connect(profileId)
            } catch (cancelled: CancellationException) {
                // Cancellation during a Keystore write must not leave a surprise profile.
                if (stored) withContext(NonCancellable + Dispatchers.IO) {
                    runCatching { profileStore.remove(profileId) }
                }
                throw cancelled
            } catch (error: Exception) {
                if (generation == currentGeneration) showError(
                    if (error is PairingException) error.message ?: "Pairing failed. Generate a new code."
                    else "Could not securely save this server. Generate a new pairing code before trying again.",
                )
            } finally {
                newClient.close()
                if (generation == currentGeneration) {
                    pairingClient = null
                    pairingJob = null
                    mutableState.update { it.copy(pairing = false) }
                }
            }
        }
    }

    fun cancelPairing() {
        if (state.value.pairing) disconnect()
    }

    fun connect(profileId: String) {
        if (!ensureNetworkPermission()) return
        releaseConnection()
        val currentGeneration = generation
        mutableState.update {
            HerdrUiState(
                profiles = it.profiles,
                activeProfileId = profileId,
                connection = ConnectionState.CONNECTING,
                pairingSuccessRevision = it.pairingSuccessRevision,
            )
        }
        connectJob = viewModelScope.launch {
            var newClient: HerdrClient? = null
            try {
                val credentials = withContext(Dispatchers.IO) { profileStore.credentials(profileId) }
                if (generation != currentGeneration) return@launch
                newClient = HerdrClient(credentials)
                client = newClient
                newClient.health() // Every profile authenticates before reading any state.
                val snapshot = newClient.snapshot()
                if (generation != currentGeneration) return@launch
                updateSnapshot(snapshot)
                mutableState.update { it.copy(connection = ConnectionState.CONNECTED, error = null) }
                startAutoRefresh()
                restartObservation()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == currentGeneration) {
                    newClient?.close()
                    client = null
                    mutableState.update { it.copy(connection = ConnectionState.ERROR, error = readableError(error)) }
                }
            }
        }
    }

    fun disconnect() {
        releaseConnection()
        mutableState.update { HerdrUiState(profiles = it.profiles, pairingSuccessRevision = it.pairingSuccessRevision) }
    }

    fun localNetworkPermissionDenied() {
        disconnect()
        showError("Allow local network access in Android Settings to connect to your Herdr server through Tailscale.")
    }

    private fun ensureNetworkPermission(): Boolean {
        if (Build.VERSION.SDK_INT < 37 ||
            getApplication<Application>().checkSelfPermission(LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED
        ) return true
        localNetworkPermissionDenied()
        return false
    }

    fun refresh() {
        val currentClient = client ?: return
        if (!ensureNetworkPermission()) return
        if (state.value.connection != ConnectionState.CONNECTED) return
        refreshJob?.cancel()
        val currentRefresh = ++refreshGeneration
        val currentGeneration = generation
        mutableState.update { it.copy(refreshing = true) }
        refreshJob = viewModelScope.launch {
            try {
                val snapshot = currentClient.snapshot()
                if (generation == currentGeneration && refreshGeneration == currentRefresh) {
                    updateSnapshot(snapshot)
                    mutableState.update { it.copy(error = null) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == currentGeneration && refreshGeneration == currentRefresh) showError(readableError(error))
            } finally {
                if (generation == currentGeneration && refreshGeneration == currentRefresh) mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    fun selectWorkspace(id: String) {
        val snapshot = state.value.snapshot ?: return
        if (snapshot.workspaces.none { it.id == id }) return
        mutableState.update {
            it.copy(workspaceId = id, tabId = snapshot.tabs.firstOrNull { tab -> tab.workspaceId == id }?.id, paneId = null)
        }
        restartObservation()
    }

    fun selectTab(id: String) {
        val tab = state.value.snapshot?.tabs?.firstOrNull { it.id == id } ?: return
        mutableState.update { it.copy(workspaceId = tab.workspaceId, tabId = tab.id, paneId = null) }
        restartObservation()
    }

    fun selectPane(id: String?) {
        if (id == null) {
            mutableState.update { it.copy(paneId = null) }
        } else {
            if (state.value.pairing) {
                cancelPairing()
                showError("Pairing was canceled. Generate a new code before trying again.")
            }
            val pane = state.value.snapshot?.panes?.firstOrNull { it.id == id } ?: return
            mutableState.update { it.copy(workspaceId = pane.workspaceId, tabId = pane.tabId, paneId = pane.id) }
        }
        restartObservation()
    }

    fun leaveTerminal() = selectPane(null)

    fun sendText(text: String, enter: Boolean = true) = sendInput(text, if (enter) listOf("enter") else emptyList())
    fun sendKeys(vararg keys: String) = sendInput(null, keys.toList())

    private fun sendInput(text: String?, keys: List<String>) {
        val currentState = state.value
        val paneId = currentState.paneId ?: return
        val currentClient = client ?: return
        if (!ensureNetworkPermission()) return
        if (currentState.connection != ConnectionState.CONNECTED || currentState.sending) return
        if (currentState.snapshot?.panes?.none { it.id == paneId } != false) {
            showError("The selected pane is no longer available. Refresh before sending.")
            return
        }
        val currentGeneration = generation
        mutableState.update { it.copy(sending = true, error = null) }
        inputJob = viewModelScope.launch {
            try {
                currentClient.input(paneId, text, keys)
                if (generation == currentGeneration && state.value.paneId == paneId && !text.isNullOrEmpty()) {
                    mutableState.update { it.copy(inputRevision = it.inputRevision + 1) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == currentGeneration) {
                    showError("${readableError(error)} Input was not retried; check the terminal before sending again.")
                }
            } finally {
                if (generation == currentGeneration) mutableState.update { it.copy(sending = false) }
            }
        }
    }

    fun createWorkspace(cwd: String, label: String) {
        val currentClient = client ?: return
        if (!ensureNetworkPermission()) return
        if (state.value.connection != ConnectionState.CONNECTED || state.value.creatingWorkspace) return
        val currentGeneration = generation
        mutableState.update { it.copy(creatingWorkspace = true, error = null) }
        createJob = viewModelScope.launch {
            try {
                val workspaceId = currentClient.createWorkspace(cwd, label)
                if (generation != currentGeneration) return@launch
                val snapshot = currentClient.snapshot()
                if (generation == currentGeneration) {
                    updateSnapshot(snapshot)
                    selectWorkspace(workspaceId)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (generation == currentGeneration) showError("${readableError(error)} Workspace creation was not retried.")
            } finally {
                if (generation == currentGeneration) mutableState.update { it.copy(creatingWorkspace = false) }
            }
        }
    }

    fun setForeground(value: Boolean) {
        if (foreground == value) return
        foreground = value
        if (value) {
            if (client != null && !ensureNetworkPermission()) return
            refresh()
            startAutoRefresh()
        } else {
            autoRefreshJob?.cancel()
            refreshJob?.cancel()
        }
        restartObservation()
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    private fun updateSnapshot(snapshot: Snapshot) {
        val previousPaneId = state.value.paneId
        mutableState.update { old ->
            val workspaceId = old.workspaceId?.takeIf { id -> snapshot.workspaces.any { it.id == id } }
                ?: snapshot.workspaces.firstOrNull()?.id
            val tabId = old.tabId?.takeIf { id -> snapshot.tabs.any { it.id == id && it.workspaceId == workspaceId } }
                ?: snapshot.tabs.firstOrNull { it.workspaceId == workspaceId }?.id
            val paneId = old.paneId?.takeIf { id -> snapshot.panes.any { it.id == id && it.tabId == tabId } }
            old.copy(snapshot = snapshot, workspaceId = workspaceId, tabId = tabId, paneId = paneId)
        }
        if (state.value.paneId != previousPaneId) restartObservation()
    }

    private fun startAutoRefresh() {
        autoRefreshJob?.cancel()
        if (!foreground || state.value.connection != ConnectionState.CONNECTED) return
        autoRefreshJob = viewModelScope.launch {
            while (foreground) {
                delay(3_000)
                if (refreshJob?.isActive != true) refresh()
            }
        }
    }

    private fun restartObservation() {
        terminalJob?.cancel()
        terminalJob = null
        val sessionId = ++terminalSession
        mutableState.update { it.copy(terminal = TerminalContent(revision = sessionId)) }
        val paneId = state.value.paneId ?: return
        val currentClient = client ?: return
        if (!ensureNetworkPermission()) return
        if (!foreground || state.value.connection != ConnectionState.CONNECTED || mutableTerminalFrames.subscriptionCount.value == 0) return
        val currentGeneration = generation
        terminalJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < MAX_OBSERVER_ATTEMPTS) {
                if (generation != currentGeneration || terminalSession != sessionId) return@launch
                mutableState.update { it.copy(terminal = it.terminal.copy(connecting = true, connected = false)) }
                try {
                    currentClient.observe(paneId, 80, 30).collect { message ->
                        if (generation != currentGeneration || terminalSession != sessionId) return@collect
                        if (message is TerminalMessage.Frame) {
                            mutableState.update {
                                it.copy(terminal = it.terminal.copy(cols = message.cols, rows = message.rows, connected = true, connecting = false))
                            }
                            mutableTerminalFrames.emit(TerminalFrame(message.ansi, message.cols, message.rows, message.full, sessionId, paneId))
                        }
                    }
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (generation != currentGeneration || terminalSession != sessionId) return@launch
                    attempts++
                    val retry = attempts < MAX_OBSERVER_ATTEMPTS && error !is TerminalEndedException &&
                        !(error is BridgeException && error.statusCode in listOf(401, 403, 404, 409))
                    mutableState.update {
                        it.copy(terminal = it.terminal.copy(connected = false, connecting = retry), error = readableError(error))
                    }
                    if (!retry) return@launch
                    delay(OBSERVER_RETRY_DELAYS[attempts - 1])
                    // Reconnects refresh reads, but never replay input.
                    refresh()
                }
            }
        }
    }

    private fun releaseConnection() {
        generation++
        terminalSession++
        listOf(connectJob, refreshJob, autoRefreshJob, terminalJob, inputJob, createJob, pairingJob).forEach { it?.cancel() }
        connectJob = null
        refreshJob = null
        autoRefreshJob = null
        terminalJob = null
        inputJob = null
        createJob = null
        pairingJob = null
        pairingClient?.close()
        pairingClient = null
        client?.close()
        client = null
    }

    private fun reloadProfiles() {
        try { mutableState.update { it.copy(profiles = profileStore.profiles()) } }
        catch (_: Exception) { showError("Could not read saved profiles. Your existing profile data has been preserved.") }
    }

    private fun showError(message: String) = mutableState.update { it.copy(error = message) }

    private fun readableError(error: Exception): String = when (error) {
        is BridgeException, is TerminalEndedException, is java.io.IOException -> error.message ?: "The server connection failed."
        is IllegalArgumentException -> error.message ?: "Check the connection details."
        is java.security.GeneralSecurityException -> "Could not decrypt the saved token. Edit this profile and enter the token again."
        else -> "The server returned an unexpected response. Check that the bridge is running and up to date."
    }

    override fun onCleared() { releaseConnection(); super.onCleared() }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
        const val MAX_OBSERVER_ATTEMPTS = 4
        val OBSERVER_RETRY_DELAYS = longArrayOf(1_000, 2_000, 4_000)
    }
}
