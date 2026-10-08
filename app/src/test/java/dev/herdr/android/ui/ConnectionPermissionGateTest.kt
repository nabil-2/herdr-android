package dev.herdr.android.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionPermissionGateTest {
    @Test
    fun noNetworkActionRunsBeforePermissionAndDenialDoesNotConnect() {
        val actions = mutableListOf<PendingNetworkAction>()
        var requests = 0
        var denials = 0
        val gate = ConnectionPermissionGate({ false }, { requests++ }, { actions += it }, { denials++ })
        gate.run(PendingNetworkAction(NetworkActionKind.CONNECT, "dust"))
        assertEquals(1, requests)
        assertTrue(actions.isEmpty())
        gate.onResult(false)
        assertEquals(1, denials)
        assertTrue(actions.isEmpty())
        assertNull(gate.pending)
        gate.onResult(true) // A stale callback must never connect a denied target.
        assertTrue(actions.isEmpty())
    }

    @Test
    fun grantResumesOnlyTheSelectedTargetOnceAfterRecreation() {
        val actions = mutableListOf<PendingNetworkAction>()
        val original = ConnectionPermissionGate({ false }, {}, { actions += it }, {})
        val selected = PendingNetworkAction(NetworkActionKind.OBSERVE, "pane-2")
        original.run(selected)
        val recreated = ConnectionPermissionGate({ true }, {}, { actions += it }, {})
        recreated.pending = original.pending // The Activity saves this ID and action kind.
        recreated.onResult(true)
        recreated.onResult(true)
        assertEquals(listOf(selected), actions)
        assertNull(recreated.pending)
    }

    @Test
    fun alreadyGrantedAccessConnectsWithoutAnotherPermissionPrompt() {
        val actions = mutableListOf<PendingNetworkAction>()
        var requests = 0
        val gate = ConnectionPermissionGate({ true }, { requests++ }, { actions += it }, {})
        val target = PendingNetworkAction(NetworkActionKind.CONNECT, "dust")
        gate.run(target)
        assertEquals(listOf(target), actions)
        assertEquals(0, requests)
    }
}
