package dev.herdr.android

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.herdr.android.data.BridgeException
import dev.herdr.android.data.ConnectionState
import dev.herdr.android.data.ChatContent
import dev.herdr.android.data.ChatDraftStore
import dev.herdr.android.data.ChatState
import dev.herdr.android.data.ConversationProtocol
import dev.herdr.android.data.HerdrClient
import dev.herdr.android.data.HerdrUiState
import dev.herdr.android.data.ProfileStore
import dev.herdr.android.data.PairingClient
import dev.herdr.android.data.PairingException
import dev.herdr.android.data.PairingPayload
import dev.herdr.android.data.Pane
import dev.herdr.android.data.PaneMode
import dev.herdr.android.data.Snapshot
import dev.herdr.android.data.TerminalContent
import dev.herdr.android.data.TerminalEndedException
import dev.herdr.android.data.TerminalFrame
import dev.herdr.android.data.TerminalMessage
import dev.herdr.android.data.InputValidation
import dev.herdr.android.data.TerminalInput
import dev.herdr.android.data.TerminalInputDecoder
import dev.herdr.android.data.TerminalInputQueue
import dev.herdr.android.data.TerminalInputQueueFullException
import dev.herdr.android.data.TerminalInputTarget
import dev.herdr.android.data.TerminalHistory
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
    private val chatDrafts = ChatDraftStore()
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
    private var paneVisible = true
    private var chatSession = 0L
    private var chatWriteSequence = 0L
    private var connectJob: Job? = null
    private var refreshJob: Job? = null
    private var autoRefreshJob: Job? = null
    private var terminalJob: Job? = null
    private var createJob: Job? = null
    private var pairingJob: Job? = null
    private var pairingClient: PairingClient? = null
    private var chatJob: Job? = null
    private var olderMessagesJob: Job? = null
    private var chatInputJob: Job? = null
    private val terminalDecoder = TerminalInputDecoder()
    // Kept in memory across selection/reconnects so an uncertain old write is not
    // silently cleared by a new observer. No input text is retained here.
    private val uncertainTerminalTargets = mutableSetOf<Pair<String, String>>()
    private val terminalInputs = TerminalInputQueue<TerminalInputTarget>(
        scope = viewModelScope,
        send = { target, input ->
            check(terminalTargetMatches(target)) { "The terminal selection changed before input was sent." }
            val currentClient = client ?: error("The terminal connection ended.")
            if (input.raw) currentClient.terminalInput(target.paneId, input.text.orEmpty())
            else currentClient.input(target.paneId, input.text, input.keys)
        },
        onBusy = { target, busy ->
            if (terminalTargetMatches(target)) mutableState.update { it.copy(sending = busy) }
        },
        onAcknowledged = { target, input ->
            if (terminalTargetMatches(target) && input.acknowledgeDraft) {
                mutableState.update { it.copy(inputRevision = it.inputRevision + 1) }
            }
        },
        onFailure = { target, error ->
            terminalDecoder.reset()
            uncertainTerminalTargets.add(target.profileId to target.paneId)
            if (terminalTargetMatches(target)) mutableState.update {
                val reason = if (error is TerminalInputQueueFullException) "Typing stopped because the terminal input queue is full."
                    else "${readableError(error)} Input delivery was not confirmed."
                it.copy(sending = false, terminal = it.terminal.copy(canInput = false, inputUncertain = true),
                    error = "$reason Pending input was discarded and will not be retried. Review the terminal before continuing.")
            }
        },
    )

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
            chatDrafts.removeProfile(id)
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
                val health = newClient.health() // Every profile authenticates before reading any state.
                val snapshot = newClient.snapshot()
                if (generation != currentGeneration) return@launch
                mutableState.update { it.copy(capabilities = health.capabilities) }
                updateSnapshot(snapshot)
                mutableState.update { it.copy(connection = ConnectionState.CONNECTED, error = null) }
                startAutoRefresh()
                restartObservation()
                restartChatPolling()
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
        stopChatWork()
        mutableState.update {
            it.copy(workspaceId = id, tabId = snapshot.tabs.firstOrNull { tab -> tab.workspaceId == id }?.id, paneId = null, chat = ChatContent())
        }
        restartObservation()
    }

    fun selectTab(id: String) {
        val tab = state.value.snapshot?.tabs?.firstOrNull { it.id == id } ?: return
        stopChatWork()
        mutableState.update { it.copy(workspaceId = tab.workspaceId, tabId = tab.id, paneId = null, chat = ChatContent()) }
        restartObservation()
    }

    fun selectPane(id: String?) {
        if (id == null) {
            stopChatWork()
            mutableState.update { it.copy(paneId = null, chat = ChatContent()) }
        } else {
            if (state.value.pairing) {
                cancelPairing()
                showError("Pairing was canceled. Generate a new code before trying again.")
            }
            val pane = state.value.snapshot?.panes?.firstOrNull { it.id == id } ?: return
            if (state.value.paneId != id) {
                stopChatWork()
                mutableState.update {
                    it.copy(workspaceId = pane.workspaceId, tabId = pane.tabId, paneId = pane.id,
                        paneMode = if (supportsChat(pane)) PaneMode.CHAT else PaneMode.TERMINAL, chat = ChatContent())
                }
            }
        }
        restartObservation()
        restartChatPolling()
    }

    fun leaveTerminal() = selectPane(null)
    fun leavePane() = selectPane(null)

    fun showTerminal() {
        if (state.value.paneId == null) return
        stopChatWork()
        mutableState.update { it.copy(paneMode = PaneMode.TERMINAL) }
        restartObservation()
    }

    fun showChat() {
        if (state.value.paneId == null) return
        mutableState.update { it.copy(paneMode = PaneMode.CHAT) }
        restartObservation()
        restartChatPolling()
    }

    fun setPaneVisible(value: Boolean) {
        if (paneVisible == value) return
        paneVisible = value
        if (!value) stopChatWork()
        restartObservation()
        if (value) restartChatPolling()
    }

    fun setChatDraft(text: String) {
        if (state.value.paneId == null || state.value.chat.bindingId == null) return
        if (text.toByteArray(Charsets.UTF_8).size > ChatDraftStore.MAX_DRAFT_BYTES) {
            mutableState.update { it.copy(chat = it.chat.copy(error = "This draft is too large. Chat messages can contain up to 32 KiB of text.")) }
            return
        }
        mutableState.update { it.copy(chat = it.chat.copy(draft = text)) }
        rememberCurrentChat()
    }

    fun refreshChat() = restartChatPolling()

    fun acknowledgeChatDelivery() {
        mutableState.update { it.copy(chat = it.chat.copy(deliveryUncertain = false, error = null)) }
        rememberCurrentChat()
    }

    fun sendChat() {
        val current = state.value
        val paneId = current.paneId ?: return
        val binding = current.chat.bindingId ?: return
        val currentClient = client ?: return
        if (!ensureNetworkPermission()) return
        if (!foreground || !paneVisible || current.paneMode != PaneMode.CHAT ||
            current.connection != ConnectionState.CONNECTED || current.chat.sending || current.chat.deliveryUncertain || !current.chat.canSend
        ) return
        val text = current.chat.draft
        if (text.isBlank()) return
        try { ConversationProtocol.input(binding, text) }
        catch (error: IllegalArgumentException) {
            mutableState.update { it.copy(chat = it.chat.copy(error = error.message ?: "Check the message before sending.")) }
            return
        }
        val currentGeneration = generation
        val writeSequence = ++chatWriteSequence
        mutableState.update { it.copy(chat = it.chat.copy(sending = true, error = null)) }
        // A snapshot can clear the selection before cancellation handles the write.
        // Record possible delivery while the original profile/pane/binding is known.
        rememberCurrentChat()
        chatInputJob = viewModelScope.launch {
            try {
                val warning = currentClient.sendMessage(paneId, binding, text)
                if (chatTargetMatches(currentGeneration, paneId, binding) && chatWriteSequence == writeSequence) {
                    mutableState.update { it.copy(chat = ChatState.acknowledged(it.chat, binding, text, warning)) }
                    rememberCurrentChat()
                    restartChatPolling()
                }
            } catch (cancelled: CancellationException) {
                if (chatTargetMatches(currentGeneration, paneId, binding) && chatWriteSequence == writeSequence) mutableState.update {
                    it.copy(chat = it.chat.copy(deliveryUncertain = true, error = "Message delivery was not confirmed. Check the conversation before sending again."))
                }
                throw cancelled
            } catch (error: Exception) {
                if (chatTargetMatches(currentGeneration, paneId, binding) && chatWriteSequence == writeSequence) mutableState.update {
                    val uncertain = error !is BridgeException || error.statusCode !in listOf(400, 401, 403, 404, 409, 413, 422)
                    it.copy(chat = it.chat.copy(canSend = false, deliveryUncertain = uncertain,
                        error = "${readableError(error)} Message was not retried${if (uncertain) "; check the conversation before sending again" else ""}."))
                }
            } finally {
                if (chatTargetMatches(currentGeneration, paneId, binding) && chatWriteSequence == writeSequence) {
                    mutableState.update { it.copy(chat = it.chat.copy(sending = false)) }
                    rememberCurrentChat()
                }
            }
        }
    }

    fun loadOlderMessages() {
        val current = state.value
        val paneId = current.paneId ?: return
        val binding = current.chat.bindingId ?: return
        val revision = current.chat.revision ?: return
        var cursor = current.chat.olderCursor ?: return
        val currentClient = client ?: return
        if (!ensureNetworkPermission()) return
        if (!foreground || !paneVisible || current.paneMode != PaneMode.CHAT || current.chat.loadingOlder) return
        val currentGeneration = generation
        val session = chatSession
        mutableState.update { it.copy(chat = it.chat.copy(loadingOlder = true, error = null)) }
        olderMessagesJob = viewModelScope.launch {
            try {
                // A new revision rebases the server's cursor. Skip already loaded overlap
                // while retaining stable message IDs and the UI's existing scroll anchor.
                repeat(20) {
                    val page = currentClient.conversation(paneId, cursor = cursor)
                    if (!chatTargetMatches(currentGeneration, paneId, binding) || chatSession != session) return@launch
                    if (state.value.chat.revision != revision) throw IllegalArgumentException("The conversation changed. Load older messages again.")
                    val knownIds = state.value.chat.messages.map { message -> message.id }.toSet()
                    val containsOlder = page.messages.any { message -> message.id !in knownIds }
                    mutableState.update { it.copy(chat = ChatState.older(it.chat, page)) }
                    val next = page.olderCursor
                    if (containsOlder || next == null || next == cursor) return@launch
                    cursor = next
                    mutableState.update { it.copy(chat = it.chat.copy(loadingOlder = true)) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (chatTargetMatches(currentGeneration, paneId, binding) && chatSession == session) {
                    mutableState.update { it.copy(chat = it.chat.copy(error = readableError(error))) }
                    if (error is BridgeException && error.statusCode == 409) restartChatPolling(resetRevision = true)
                }
            } finally {
                if (chatTargetMatches(currentGeneration, paneId, binding) && chatSession == session) mutableState.update { it.copy(chat = it.chat.copy(loadingOlder = false)) }
            }
        }
    }

    fun sendText(text: String, enter: Boolean = true): Boolean =
        sendInput(text, if (enter) listOf("enter") else emptyList(), acknowledgeDraft = text.isNotEmpty())

    fun sendKeys(vararg keys: String): Boolean = sendInput(null, keys.toList())

    /** Capture once per UI render so a delayed old keyboard cannot target a new pane. */
    internal fun captureTerminalInputScope(profileId: String?, paneId: String, sessionId: Long): TerminalInputTarget? =
        terminalInputs.target?.takeIf {
            it.profileId == profileId && it.paneId == paneId && it.sessionId == sessionId && terminalTargetMatches(it)
        }

    internal fun sendTerminalInput(scope: TerminalInputTarget?, bytes: ByteArray): Boolean =
        currentInputScope(scope) && sendTerminalInput(bytes)

    internal fun sendKeys(scope: TerminalInputTarget?, keys: List<String>): Boolean =
        currentInputScope(scope) && sendInput(null, keys.toList())

    internal fun sendText(scope: TerminalInputTarget?, text: String, enter: Boolean = true): Boolean =
        currentInputScope(scope) && sendText(text, enter)

    private fun currentInputScope(scope: TerminalInputTarget?): Boolean =
        scope != null && scope == terminalInputs.target && terminalTargetMatches(scope)

    /** Accepts only user-generated keyboard/IME input, never emulator replies. */
    fun sendTerminalInput(bytes: ByteArray): Boolean {
        val target = inputTarget() ?: return false
        if ("terminal-input" !in state.value.capabilities) {
            showError("Update the host companion for direct keyboard input. The command composer and special keys still work.")
            return false
        }
        if (bytes.isEmpty()) return true
        val decoded = try { terminalDecoder.preview(bytes) }
        catch (error: IllegalArgumentException) {
            terminalDecoder.reset()
            showError(error.message ?: "Check the terminal input.")
            return false
        }
        val inputs = TerminalInputDecoder.chunks(decoded.text)
        if (inputs.isNotEmpty() && !terminalInputs.offer(target, inputs)) return false
        terminalDecoder.accept(decoded)
        return true
    }

    private fun sendInput(text: String?, keys: List<String>, acknowledgeDraft: Boolean = false): Boolean {
        val target = inputTarget() ?: return false
        if (terminalDecoder.hasPending) {
            showError("Finish the current character before sending a special key or command.")
            return false
        }
        try { InputValidation.body(text, keys) }
        catch (error: IllegalArgumentException) { showError(error.message ?: "Check the input before sending."); return false }
        return terminalInputs.offer(target, listOf(TerminalInput(text, keys.toList(), acknowledgeDraft = acknowledgeDraft)))
    }

    fun acknowledgeTerminalInput() {
        val target = terminalInputs.target ?: return
        if (!terminalTargetMatches(target) || !state.value.terminal.connected || !foreground || !paneVisible) return
        uncertainTerminalTargets.remove(target.profileId to target.paneId)
        terminalInputs.resume(target)
        mutableState.update { it.copy(terminal = it.terminal.copy(canInput = true, inputUncertain = false), error = null) }
    }

    /** The calling sheet owns and cancels this read when its lifecycle ends. */
    suspend fun readTerminalHistory(): TerminalHistory {
        check(ensureNetworkPermission()) { "Allow local network access before reading terminal output." }
        val current = state.value
        val paneId = current.paneId ?: throw IllegalStateException("Select a terminal before reading recent output.")
        val profileId = current.activeProfileId ?: throw IllegalStateException("Connect to a server before reading recent output.")
        val currentClient = client ?: throw IllegalStateException("Connect to a server before reading recent output.")
        check(foreground && paneVisible && current.paneMode == PaneMode.TERMINAL && current.connection == ConnectionState.CONNECTED) {
            "Open the terminal before reading recent output."
        }
        check("terminal-history" in current.capabilities) { "Update the host companion to read recent terminal output." }
        check(current.snapshot?.panes?.any { it.id == paneId } == true) { "The selected pane is no longer available. Refresh the server." }
        val currentGeneration = generation
        val currentSession = terminalSession
        val history = currentClient.terminalHistory(paneId)
        if (generation != currentGeneration || terminalSession != currentSession || state.value.activeProfileId != profileId ||
            state.value.paneId != paneId || !foreground || !paneVisible || state.value.paneMode != PaneMode.TERMINAL ||
            state.value.connection != ConnectionState.CONNECTED
        ) throw CancellationException("The selected terminal changed before recent output was received.")
        return history
    }

    private fun inputTarget(): TerminalInputTarget? {
        if (!ensureNetworkPermission()) return null
        val current = state.value
        val target = terminalInputs.target
        if (target == null || !terminalTargetMatches(target) || !foreground || !paneVisible ||
            current.connection != ConnectionState.CONNECTED || current.paneMode != PaneMode.TERMINAL ||
            !current.terminal.connected || !current.terminal.canInput || current.terminal.inputUncertain
        ) {
            if (!current.terminal.inputUncertain) showError("Wait for the live terminal connection before typing.")
            return null
        }
        if (current.snapshot?.panes?.none { it.id == target.paneId } != false) {
            showError("The selected pane is no longer available. Refresh before sending.")
            return null
        }
        return target
    }

    private fun terminalTargetMatches(target: TerminalInputTarget): Boolean =
        generation == target.generation && terminalSession == target.sessionId &&
            state.value.activeProfileId == target.profileId && state.value.paneId == target.paneId &&
            state.value.paneMode == PaneMode.TERMINAL

    private fun stopTerminalInput() {
        val target = terminalInputs.target
        val discarded = terminalInputs.clear()
        val partialCharacter = terminalDecoder.reset()
        if (target != null && discarded.inFlight) uncertainTerminalTargets.add(target.profileId to target.paneId)
        mutableState.update {
            val identity = it.activeProfileId?.let { profile -> it.paneId?.let { pane -> profile to pane } }
            it.copy(sending = false, terminal = it.terminal.copy(canInput = false,
                inputUncertain = identity != null && identity in uncertainTerminalTargets), error = if (discarded.any || partialCharacter) {
                    "Terminal input was interrupted. Pending input was discarded and will not be retried." +
                        if (discarded.inFlight) " Check the terminal before continuing." else ""
                } else it.error)
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
            stopChatWork()
        }
        restartObservation()
        if (value) restartChatPolling()
    }

    fun clearError() = mutableState.update { it.copy(error = null) }

    private fun updateSnapshot(snapshot: Snapshot) {
        val previousPaneId = state.value.paneId
        val previousAvailability = state.value.snapshot?.panes?.firstOrNull { it.id == previousPaneId }?.conversation
        mutableState.update { old ->
            val workspaceId = old.workspaceId?.takeIf { id -> snapshot.workspaces.any { it.id == id } }
                ?: snapshot.workspaces.firstOrNull()?.id
            val tabId = old.tabId?.takeIf { id -> snapshot.tabs.any { it.id == id && it.workspaceId == workspaceId } }
                ?: snapshot.tabs.firstOrNull { it.workspaceId == workspaceId }?.id
            val paneId = old.paneId?.takeIf { id -> snapshot.panes.any { it.id == id && it.tabId == tabId } }
            old.copy(snapshot = snapshot, workspaceId = workspaceId, tabId = tabId, paneId = paneId)
        }
        if (state.value.paneId != previousPaneId) {
            stopChatWork()
            mutableState.update { it.copy(chat = ChatContent()) }
            restartObservation()
            restartChatPolling()
        } else if (snapshot.panes.firstOrNull { it.id == previousPaneId }?.conversation != previousAvailability) {
            restartChatPolling()
        }
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
        stopTerminalInput()
        terminalJob?.cancel()
        terminalJob = null
        val sessionId = ++terminalSession
        mutableState.update {
            val identity = it.activeProfileId?.let { profile -> it.paneId?.let { pane -> profile to pane } }
            it.copy(terminal = TerminalContent(revision = sessionId, inputUncertain = identity != null && identity in uncertainTerminalTargets))
        }
        val paneId = state.value.paneId ?: return
        val currentClient = client ?: return
        if (!ensureNetworkPermission()) return
        if (!foreground || !paneVisible || state.value.paneMode != PaneMode.TERMINAL || state.value.connection != ConnectionState.CONNECTED || mutableTerminalFrames.subscriptionCount.value == 0) return
        val currentGeneration = generation
        terminalJob = viewModelScope.launch {
            var attempts = 0
            while (attempts < MAX_OBSERVER_ATTEMPTS) {
                if (generation != currentGeneration || terminalSession != sessionId) return@launch
                val profileId = state.value.activeProfileId ?: return@launch
                val inputTarget = TerminalInputTarget(profileId, currentGeneration, paneId, sessionId, attempts)
                mutableState.update { it.copy(terminal = it.terminal.copy(connecting = true, connected = false)) }
                try {
                    currentClient.observe(paneId, 80, 30).collect { message ->
                        if (generation != currentGeneration || terminalSession != sessionId) return@collect
                        if (message is TerminalMessage.Frame) {
                            terminalInputs.activate(inputTarget)
                            mutableState.update {
                                val uncertain = (inputTarget.profileId to inputTarget.paneId) in uncertainTerminalTargets
                                it.copy(terminal = it.terminal.copy(cols = message.cols, rows = message.rows, connected = true, connecting = false,
                                    canInput = !uncertain, inputUncertain = uncertain))
                            }
                            mutableTerminalFrames.emit(TerminalFrame(message.ansi, message.cols, message.rows, message.full, sessionId, paneId))
                        }
                    }
                    return@launch
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    if (generation != currentGeneration || terminalSession != sessionId) return@launch
                    stopTerminalInput()
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

    private fun supportsChat(pane: Pane): Boolean = ChatState.supportsPaneChat(pane, state.value.capabilities)

    private fun chatTargetMatches(currentGeneration: Long, paneId: String, binding: String): Boolean =
        generation == currentGeneration && state.value.paneId == paneId && state.value.chat.bindingId == binding

    private fun stopChatWork() {
        chatSession++
        chatWriteSequence++
        val pendingWrite = chatInputJob?.isActive == true
        listOf(chatJob, olderMessagesJob, chatInputJob).forEach { it?.cancel() }
        chatJob = null
        olderMessagesJob = null
        chatInputJob = null
        mutableState.update {
            it.copy(chat = it.chat.copy(loading = false, refreshing = false, loadingOlder = false, sending = false, canSend = false,
                deliveryUncertain = pendingWrite || it.chat.deliveryUncertain,
                error = if (pendingWrite) "Message delivery was not confirmed. Check the conversation before sending again." else it.chat.error))
        }
        rememberCurrentChat()
    }

    private fun rememberCurrentChat() {
        val current = state.value
        val profileId = current.activeProfileId ?: return
        val paneId = current.paneId ?: return
        chatDrafts.remember(profileId, paneId, current.chat)
    }

    private fun restartChatPolling(resetRevision: Boolean = false) {
        chatJob?.cancel()
        olderMessagesJob?.cancel()
        chatJob = null
        olderMessagesJob = null
        val session = ++chatSession
        mutableState.update {
            it.copy(chat = it.chat.copy(loading = false, refreshing = false, loadingOlder = false,
                revision = if (resetRevision) null else it.chat.revision, canSend = false))
        }
        val current = state.value
        val paneId = current.paneId ?: return
        if (current.paneMode != PaneMode.CHAT) return
        val pane = current.snapshot?.panes?.firstOrNull { it.id == paneId } ?: return
        if (!supportsChat(pane)) {
            val reason = when {
                "codex-chat" !in current.capabilities -> "This host companion does not support native Codex chat. Use Terminal or update the companion."
                pane.conversation?.provider != "codex" -> "Native chat is available for confirmed Codex conversations. Use Terminal for this pane."
                else -> pane.conversation.reason ?: "This Codex conversation is unavailable. Use Terminal to inspect it."
            }
            mutableState.update { it.copy(chat = it.chat.copy(error = reason, canSend = false)) }
            return
        }
        val currentClient = client ?: return
        if (!foreground || !paneVisible || current.connection != ConnectionState.CONNECTED || !ensureNetworkPermission()) return
        val currentGeneration = generation
        chatJob = viewModelScope.launch {
            var failures = 0
            while (foreground && paneVisible && chatSession == session && generation == currentGeneration) {
                try {
                    if (!ensureNetworkPermission()) return@launch
                    mutableState.update { it.copy(chat = it.chat.copy(loading = it.chat.bindingId == null, refreshing = true)) }
                    var page = currentClient.conversation(paneId, revision = state.value.chat.revision)
                    if (chatSession != session || generation != currentGeneration || state.value.paneId != paneId) return@launch
                    if (ChatState.requiresFullPage(state.value.chat, page)) {
                        page = currentClient.conversation(paneId)
                    }
                    if (chatSession != session || generation != currentGeneration || state.value.paneId != paneId) return@launch
                    val previousBinding = state.value.chat.bindingId
                    if (previousBinding != page.binding) rememberCurrentChat()
                    mutableState.update {
                        val updated = ChatState.latest(it.chat, page)
                        val restored = if (previousBinding != page.binding && it.activeProfileId != null) {
                            chatDrafts.restore(it.activeProfileId, paneId, updated)
                        } else updated
                        it.copy(chat = restored)
                    }
                    if (previousBinding != null && previousBinding != page.binding) {
                        chatWriteSequence++
                        chatInputJob?.cancel()
                        chatInputJob = null
                    }
                    failures = 0
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) {
                    if (chatSession != session || generation != currentGeneration || state.value.paneId != paneId) return@launch
                    mutableState.update { it.copy(chat = it.chat.copy(loading = false, refreshing = false, canSend = false,
                        error = if (it.chat.deliveryUncertain) it.chat.error else readableError(error))) }
                    if (error is BridgeException && error.statusCode in listOf(401, 403, 404)) return@launch
                    failures++
                }
                delay(if (failures == 0) 1_000 else CHAT_READ_RETRY_DELAYS[(failures - 1).coerceAtMost(CHAT_READ_RETRY_DELAYS.lastIndex)])
            }
        }
    }

    private fun releaseConnection() {
        stopTerminalInput()
        generation++
        terminalSession++
        stopChatWork()
        listOf(connectJob, refreshJob, autoRefreshJob, terminalJob, createJob, pairingJob).forEach { it?.cancel() }
        connectJob = null
        refreshJob = null
        autoRefreshJob = null
        terminalJob = null
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
        val CHAT_READ_RETRY_DELAYS = longArrayOf(1_000, 2_000, 4_000, 8_000)
    }
}
