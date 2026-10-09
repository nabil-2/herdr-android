package dev.herdr.android.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class SnapshotProtocolTest {
    private val valid = Snapshot(
        herdrVersion = "0.9.3",
        workspaces = listOf(Workspace("w1", "Project", "/synthetic/first"), Workspace("w2", "Other", "/synthetic/other")),
        tabs = listOf(Tab("w1:t1", "w1", "Main"), Tab("w2:t1", "w2", "Main")),
        panes = listOf(Pane("w1:p1", "w1", "w1:t1", "Shell", "/synthetic/first"), Pane("w2:p1", "w2", "w2:t1", "Agent", "/synthetic/other", "claude", "blocked")),
    )

    @Test fun validSnapshotsAndEmptySessionsRemainCompatible() {
        assertEquals(valid, SnapshotProtocol.decode(Json.encodeToString(valid)))
        assertEquals(Snapshot(), SnapshotProtocol.decode("""{"workspaces":[],"tabs":[],"panes":[],"futureField":{"nested":true}}"""))
    }

    @Test fun duplicateListIdsCannotReachComposableKeys() {
        rejected(valid.copy(workspaces = valid.workspaces + valid.workspaces.first()))
        rejected(valid.copy(tabs = valid.tabs + valid.tabs.first()))
        rejected(valid.copy(panes = valid.panes + valid.panes.first()))
        rejected(valid.copy(tabs = listOf(valid.tabs[0], valid.tabs[1].copy(id = valid.tabs[0].id))))
    }

    @Test fun blankIdsCannotBecomeSelectableTargets() {
        rejected(valid.copy(workspaces = listOf(valid.workspaces.first().copy(id = " "))))
        rejected(valid.copy(tabs = listOf(valid.tabs.first().copy(id = ""))))
        rejected(valid.copy(panes = listOf(valid.panes.first().copy(id = "\t"))))
    }

    @Test fun orphanedAndCrossWorkspaceReferencesAreRejected() {
        rejected(valid.copy(tabs = listOf(valid.tabs.first().copy(workspaceId = "missing"))))
        rejected(valid.copy(panes = listOf(valid.panes.first().copy(workspaceId = "missing"))))
        rejected(valid.copy(panes = listOf(valid.panes.first().copy(tabId = "missing"))))
        rejected(valid.copy(panes = listOf(valid.panes.first().copy(tabId = "w2:t1"))))
    }

    @Test fun malformedAndWrongTypedPayloadsProduceControlledErrorsWithoutEchoedData() {
        listOf("not json secret fixture", "[]", "{}", """{"workspaces":{},"tabs":[],"panes":[]}""", """{"workspaces":[],"tabs":[],"panes":[{"id":"secret fixture"}]}""").forEach { payload ->
            val error = assertThrows(IllegalArgumentException::class.java) { SnapshotProtocol.decode(payload) }
            assertEquals(SnapshotProtocol.ERROR, error.message)
            assertEquals(null, error.cause)
            assertFalse(error.toString().contains("secret fixture"))
        }
    }

    @Test fun deeplyNestedUnknownInputIsRejectedBeforeRecursiveParsing() {
        val nested = "[".repeat(200) + "0" + "]".repeat(200)
        val error = assertThrows(IllegalArgumentException::class.java) {
            SnapshotProtocol.decode("""{"workspaces":[],"tabs":[],"panes":[],"unknown":$nested}""")
        }
        assertEquals(SnapshotProtocol.ERROR, error.message)
    }

    @Test fun bracesAndEscapedQuotesInLabelsDoNotAffectNestingValidation() {
        val changed = valid.copy(workspaces = valid.workspaces.map { it.copy(label = "Quoted \"[{workspace}]\" \\ path") })
        assertEquals(changed, SnapshotProtocol.decode(Json.encodeToString(changed)))
    }

    private fun rejected(snapshot: Snapshot) {
        val error = assertThrows(IllegalArgumentException::class.java) { SnapshotProtocol.decode(Json.encodeToString(snapshot)) }
        assertEquals(SnapshotProtocol.ERROR, error.message)
        assertEquals(null, error.cause)
    }
}
