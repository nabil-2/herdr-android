package dev.herdr.android.ui

internal enum class NetworkActionKind { CONNECT, OBSERVE, PAIR }
internal data class PendingNetworkAction(val kind: NetworkActionKind, val id: String) {
    override fun toString(): String = "PendingNetworkAction(kind=$kind, value=<redacted>)"
}

/** Holds only the user's selected target while Android asks for network permission. */
internal class ConnectionPermissionGate(
    private val hasPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
    private val execute: (PendingNetworkAction) -> Unit,
    private val onDenied: () -> Unit,
) {
    var pending: PendingNetworkAction? = null

    // A QR invitation contains a one-use secret and must stay out of SavedInstanceState.
    fun restorableAction(): PendingNetworkAction? = pending?.takeUnless { it.kind == NetworkActionKind.PAIR }

    fun run(action: PendingNetworkAction) {
        if (hasPermission()) {
            pending = null
            execute(action)
        } else {
            pending = action
            requestPermission()
        }
    }

    fun onResult(granted: Boolean) {
        val action = pending ?: return
        pending = null
        if (granted) execute(action) else onDenied()
    }
}
