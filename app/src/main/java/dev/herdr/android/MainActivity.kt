package dev.herdr.android

import android.os.Bundle
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.ComposeUiFlags
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModelProvider
import dev.herdr.android.ui.HerdrApp
import dev.herdr.android.ui.ConnectionPermissionGate
import dev.herdr.android.ui.NetworkActionKind
import dev.herdr.android.ui.PendingNetworkAction

class MainActivity : ComponentActivity() {
    private lateinit var herdr: HerdrViewModel
    private lateinit var permissionGate: ConnectionPermissionGate
    private val localNetworkPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGate.onResult(granted)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Clear native-view focus before removal so pane switches cannot trigger
        // a nested Compose measurement through Android's automatic focus search.
        ComposeUiFlags.isViewFocusFixEnabled = true
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        herdr = ViewModelProvider(this)[HerdrViewModel::class.java]
        permissionGate = ConnectionPermissionGate(
            hasPermission = ::hasLocalNetworkPermission,
            requestPermission = { localNetworkPermission.launch(LOCAL_NETWORK_PERMISSION) },
            execute = ::executeNetworkAction,
            onDenied = herdr::localNetworkPermissionDenied,
        )
        val restoredKind = NetworkActionKind.entries.firstOrNull { it != NetworkActionKind.PAIR && it.name == savedInstanceState?.getString("pending_network_kind") }
        val restoredId = savedInstanceState?.getString("pending_network_id")
        if (restoredKind != null && restoredId != null) permissionGate.pending = PendingNetworkAction(restoredKind, restoredId)
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> herdr.setForeground(true)
                Lifecycle.Event.ON_STOP -> herdr.setForeground(false)
                else -> Unit
            }
        })
        setContent { HerdrApp(herdr, ::connectWithNetworkPermission, ::retryTerminalWithNetworkPermission, ::pairWithNetworkPermission) }
    }

    private fun hasLocalNetworkPermission(): Boolean = Build.VERSION.SDK_INT < 37 ||
        ContextCompat.checkSelfPermission(this, LOCAL_NETWORK_PERMISSION) == PackageManager.PERMISSION_GRANTED

    private fun connectWithNetworkPermission(profileId: String) {
        permissionGate.run(PendingNetworkAction(NetworkActionKind.CONNECT, profileId))
    }

    private fun retryTerminalWithNetworkPermission(paneId: String) {
        permissionGate.run(PendingNetworkAction(NetworkActionKind.OBSERVE, paneId))
    }

    private fun pairWithNetworkPermission(payload: String) {
        permissionGate.run(PendingNetworkAction(NetworkActionKind.PAIR, payload))
    }

    private fun executeNetworkAction(action: PendingNetworkAction) {
        when (action.kind) {
            NetworkActionKind.CONNECT -> if (herdr.state.value.profiles.any { it.id == action.id }) herdr.connect(action.id)
            NetworkActionKind.OBSERVE -> if (herdr.state.value.paneId == action.id) herdr.selectPane(action.id)
            NetworkActionKind.PAIR -> herdr.pairServer(action.id)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val restorable = permissionGate.restorableAction()
        outState.putString("pending_network_kind", restorable?.kind?.name)
        outState.putString("pending_network_id", restorable?.id)
        super.onSaveInstanceState(outState)
    }

    private companion object {
        const val LOCAL_NETWORK_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
