package dev.herdr.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.herdr.android.data.ConnectionState
import dev.herdr.android.data.HerdrUiState
import dev.herdr.android.HerdrViewModel
import dev.herdr.android.data.Pane
import dev.herdr.android.data.ServerProfile

@Composable
fun HerdrApp(viewModel: HerdrViewModel, onConnect: (String) -> Unit, onTerminalRetry: (String) -> Unit) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showServers by rememberSaveable { mutableStateOf(false) }
    var showAddServer by rememberSaveable { mutableStateOf(false) }
    var editingProfileId by rememberSaveable { mutableStateOf<String?>(null) }
    val editingProfile = state.profiles.firstOrNull { it.id == editingProfileId }
    var showNewWorkspace by rememberSaveable { mutableStateOf(false) }
    val serversVisible = showServers || state.activeProfileId == null
    BackHandler(enabled = showServers && state.activeProfileId != null) { showServers = false }

    HerdrTheme {
        when {
            serversVisible -> ServersScreen(
                state = state,
                canGoBack = state.activeProfileId != null,
                onBack = { showServers = false },
                onAdd = { viewModel.clearError(); editingProfileId = null; showAddServer = true },
                onEdit = { viewModel.clearError(); editingProfileId = it.id; showAddServer = true },
                onConnect = {
                    if (it != state.activeProfileId || state.connection != ConnectionState.CONNECTED) onConnect(it)
                    showServers = false
                },
                onDelete = viewModel::removeProfile,
                onDismissError = viewModel::clearError,
            )
            state.paneId != null -> TerminalScreen(
                state, viewModel,
                onBack = viewModel::leaveTerminal,
                onRetry = onTerminalRetry,
            )
            else -> DashboardScreen(
                state = state,
                onServers = { showServers = true },
                onRefresh = viewModel::refresh,
                onWorkspace = viewModel::selectWorkspace,
                onTab = viewModel::selectTab,
                onPane = viewModel::selectPane,
                onCreate = { viewModel.clearError(); showNewWorkspace = true },
                onDismissError = viewModel::clearError,
                onRetry = { state.activeProfileId?.let(onConnect) },
            )
        }
        if (showAddServer) AddServerDialog(
            profile = editingProfile,
            error = state.error,
            onDismiss = { showAddServer = false; viewModel.clearError() },
            onSave = { name, url, token ->
                if (viewModel.saveProfile(name, url, token, editingProfile?.id)) {
                    showAddServer = false
                    showServers = false
                    val id = editingProfile?.id ?: viewModel.state.value.profiles.lastOrNull()?.id
                    id?.let(onConnect)
                }
            },
        )
        if (showNewWorkspace) WorkspaceDialog(
            state = state,
            onDismiss = { showNewWorkspace = false; viewModel.clearError() },
            onCreate = { cwd, name -> viewModel.createWorkspace(cwd, name) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServersScreen(
    state: HerdrUiState,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (ServerProfile) -> Unit,
    onConnect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismissError: () -> Unit,
) {
    var deleteProfile by remember { mutableStateOf<ServerProfile?>(null) }
    Scaffold(
        containerColor = CanvasColor,
        topBar = {
            TopAppBar(
                title = { Text("Your servers", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    if (canGoBack) IconButton(onClick = onBack) { SymbolIcon(Symbol.BACK, "Back to workspaces") }
                },
                actions = { IconButton(onClick = onAdd) { SymbolIcon(Symbol.PLUS, "Add server") } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasColor),
            )
        },
        floatingActionButton = {
            if (state.profiles.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = onAdd, icon = { SymbolIcon(Symbol.PLUS) }, text = { Text("Add server") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    BrandMark()
                    Text("Your agents. Anywhere.", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Connect to the Herdr bridge on your computer. Join the same Tailscale network on your phone first.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.error != null) item { ErrorNotice(state.error, onDismissError) }
            if (state.profiles.isEmpty()) item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        SymbolIcon(Symbol.SERVER)
                        Text("Add your first server", style = MaterialTheme.typography.titleMedium)
                        Text("Use the HTTPS address and access token from your bridge setup. Your token is stored encrypted on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onAdd, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Connect a server") }
                    }
                }
            }
            items(state.profiles, key = { it.id }) { profile ->
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SymbolIcon(Symbol.SERVER)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(profile.baseUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { deleteProfile = profile }) { SymbolIcon(Symbol.DELETE, "Remove ${profile.name}") }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            TextButton(onClick = { onEdit(profile) }) { Text("Edit") }
                            Button(onClick = { onConnect(profile.id) }) { Text(if (profile.id == state.activeProfileId && state.connection == ConnectionState.CONNECTED) "Open" else "Connect") }
                        }
                    }
                }
            }
            item {
                Text("Desktop sessions keep running when you close the app.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    deleteProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfile = null },
            title = { Text("Remove ${profile.name}?") },
            text = { Text("This removes its saved address and access token from this phone.") },
            confirmButton = { TextButton(onClick = { onDelete(profile.id); deleteProfile = null }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { deleteProfile = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun BrandMark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(color = Mint, shape = MaterialTheme.shapes.small, modifier = Modifier.size(40.dp)) {
            Box(contentAlignment = Alignment.Center) { Text("h", color = Color(0xFF143223), fontWeight = FontWeight.Bold, fontSize = 28.sp, fontFamily = FontFamily.Monospace) }
        }
        Text("herdr pocket", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashboardScreen(
    state: HerdrUiState,
    onServers: () -> Unit,
    onRefresh: () -> Unit,
    onWorkspace: (String) -> Unit,
    onTab: (String) -> Unit,
    onPane: (String) -> Unit,
    onCreate: () -> Unit,
    onDismissError: () -> Unit,
    onRetry: () -> Unit,
) {
    val snapshot = state.snapshot
    val selectedWorkspace = snapshot?.workspaces?.firstOrNull { it.id == state.workspaceId }
    val tabs = snapshot?.tabs.orEmpty().filter { it.workspaceId == state.workspaceId }
    val panes = snapshot?.panes.orEmpty().filter { it.workspaceId == state.workspaceId && (state.tabId == null || it.tabId == state.tabId) }
    val needsInput = panes.filter { it.status == "blocked" }
    val otherPanes = panes.filter { it.status != "blocked" }
    val server = state.profiles.firstOrNull { it.id == state.activeProfileId }
    Scaffold(
        containerColor = CanvasColor,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Workspaces", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            ConnectionDot(state.connection)
                            Text(server?.name ?: "Herdr", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onRefresh, enabled = !state.refreshing && state.connection == ConnectionState.CONNECTED) {
                        if (state.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) else SymbolIcon(Symbol.REFRESH, "Refresh workspaces")
                    }
                    IconButton(onClick = onServers) { SymbolIcon(Symbol.SERVER, "Manage servers") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = CanvasColor),
            )
        },
        floatingActionButton = {
            if (state.connection == ConnectionState.CONNECTED) ExtendedFloatingActionButton(
                onClick = onCreate, icon = { SymbolIcon(Symbol.PLUS) }, text = { Text("Workspace") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(bottom = 112.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state.connection == ConnectionState.CONNECTING) item {
                Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text("Connecting to your server…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (state.error != null) item { ErrorNotice(state.error, onDismissError, Modifier.padding(horizontal = 20.dp)) }
            if (state.connection == ConnectionState.ERROR || state.connection == ConnectionState.DISCONNECTED) item {
                OutlinedCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Server disconnected", style = MaterialTheme.typography.titleMedium)
                        Text("Check Tailscale and the bridge on your computer, then reconnect.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Button(onClick = onRetry) { Text("Reconnect") }
                    }
                }
            }
            if (snapshot?.workspaces?.isNotEmpty() == true) {
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(snapshot.workspaces, key = { it.id }) { workspace ->
                            FilterChip(
                                selected = workspace.id == state.workspaceId,
                                onClick = { onWorkspace(workspace.id) },
                                label = { Text(workspace.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier.heightIn(min = 48.dp),
                            )
                        }
                    }
                }
                item {
                    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(selectedWorkspace?.label ?: "Select a workspace", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        selectedWorkspace?.let { Text(it.cwd, color = MaterialTheme.colorScheme.onSurfaceVariant, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                    }
                }
                if (tabs.isNotEmpty()) item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(tabs, key = { it.id }) { tab ->
                            FilterChip(selected = state.tabId == tab.id, onClick = { onTab(tab.id) }, label = { Text(tab.label) }, modifier = Modifier.heightIn(min = 48.dp))
                        }
                    }
                }
                if (needsInput.isNotEmpty()) {
                    item { GroupLabel("Needs your input", "${needsInput.size}", Amber) }
                    items(needsInput, key = { it.id }) { PaneCard(it, onPane) }
                }
                if (otherPanes.isNotEmpty()) {
                    item { GroupLabel("Agents & terminals", "${otherPanes.size}") }
                    items(otherPanes, key = { it.id }) { PaneCard(it, onPane) }
                }
                if (panes.isEmpty()) item {
                    EmptyPanel("No terminals in this tab", "Open a terminal in Herdr on your computer, or create a new workspace here.")
                }
            } else if (state.connection == ConnectionState.CONNECTED && snapshot != null) item {
                EmptyPanel("Ready for your first workspace", "Create a workspace to open a shell on your server. Then launch your preferred agent from its terminal.")
            } else if (snapshot == null && state.connection == ConnectionState.CONNECTED) item {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            }
            if (snapshot?.herdrVersion?.isNotBlank() == true) item {
                Text("HERDR ${snapshot.herdrVersion}", modifier = Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun GroupLabel(label: String, count: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = color, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Text(count, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun PaneCard(pane: Pane, onOpen: (String) -> Unit) {
    OutlinedCard(onClick = { onOpen(pane.id) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small, modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { SymbolIcon(Symbol.TERMINAL) }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pane.title.ifBlank { pane.agent ?: "Terminal" }, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusPill(pane.status)
                    pane.agent?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1) }
                }
            }
            SymbolIcon(Symbol.NEXT, "Open terminal")
        }
    }
}

@Composable
internal fun StatusPill(status: String) {
    val (label, color) = when (status) {
        "working" -> "Working" to Mint
        "blocked" -> "Needs input" to Amber
        "done" -> "Done" to Color(0xFFB9C3FB)
        "idle" -> "Idle" to Color(0xFFA9BFD7)
        else -> "Unknown" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = color.copy(alpha = 0.10f), shape = CircleShape) {
        Row(Modifier.padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(5.dp).background(color, CircleShape))
            Text(label, color = color, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun ConnectionDot(connection: ConnectionState) {
    val color = when (connection) { ConnectionState.CONNECTED -> Mint; ConnectionState.CONNECTING -> Amber; else -> MaterialTheme.colorScheme.error }
    Box(Modifier.size(6.dp).background(color, CircleShape).semantics { contentDescription = "Server ${connection.name.lowercase()}" })
}

@Composable
internal fun ErrorNotice(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall)
            IconButton(onClick = onDismiss) { SymbolIcon(Symbol.CLOSE, "Dismiss error") }
        }
    }
}

@Composable
private fun EmptyPanel(title: String, body: String) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SymbolIcon(Symbol.TERMINAL)
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun AddServerDialog(profile: ServerProfile?, error: String?, onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by rememberSaveable(profile?.id) { mutableStateOf(profile?.name.orEmpty()) }
    var url by rememberSaveable(profile?.id) { mutableStateOf(profile?.baseUrl.orEmpty()) }
    // Credentials are deliberately excluded from SavedInstanceState.
    var token by remember { mutableStateOf("") }
    var showToken by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (profile == null) "Connect a server" else "Edit server") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Server name") }, placeholder = { Text("Dust") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(url, { url = it }, label = { Text("Bridge address") }, placeholder = { Text("https://dust.your-tailnet.ts.net") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    token, { token = it }, label = { Text(if (profile == null) "Access token" else "Replacement access token") }, singleLine = true,
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show") } },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Use the HTTPS URL from Tailscale Serve. HTTP is available for private development only, using loopback or a numeric Tailscale address.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (error != null) Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, url, token) }, enabled = name.isNotBlank() && url.isNotBlank() && token.isNotBlank()) { Text("Save & connect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun WorkspaceDialog(state: HerdrUiState, onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    var cwd by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var attemptedCreation by remember { mutableStateOf(false) }
    val initialWorkspaceIds = remember { state.snapshot?.workspaces.orEmpty().map { it.id }.toSet() }
    androidx.compose.runtime.LaunchedEffect(state.workspaceId, state.creatingWorkspace, state.error) {
        if (attemptedCreation && !state.creatingWorkspace && state.error == null && state.workspaceId != null && state.workspaceId !in initialWorkspaceIds) onDismiss()
    }
    AlertDialog(
        onDismissRequest = { if (!state.creatingWorkspace) onDismiss() },
        title = { Text("New workspace") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Open a shell in a directory on your server.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(name, { name = it }, label = { Text("Workspace name") }, singleLine = true, enabled = !state.creatingWorkspace, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(cwd, { cwd = it }, label = { Text("Server directory") }, placeholder = { Text("/home/you/project") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), singleLine = true, enabled = !state.creatingWorkspace, modifier = Modifier.fillMaxWidth())
                if (state.error != null) Text(state.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                if (state.creatingWorkspace) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { attemptedCreation = true; onCreate(cwd, name) }, enabled = cwd.isNotBlank() && name.isNotBlank() && !state.creatingWorkspace) { Text("Create") } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !state.creatingWorkspace) { Text("Cancel") } },
    )
}
