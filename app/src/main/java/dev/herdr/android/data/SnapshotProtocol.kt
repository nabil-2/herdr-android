package dev.herdr.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** Validate external list identities before Compose uses them as item keys. */
internal object SnapshotProtocol {
    const val ERROR = "The server returned an invalid workspace snapshot. Reconnect or update the host companion."
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun decode(payload: String): Snapshot {
        try {
            require(payload.length <= 2 * 1024 * 1024 && payload.toByteArray(Charsets.UTF_8).size <= 2 * 1024 * 1024)
            requireBoundedNesting(payload)
            val root = json.parseToJsonElement(payload) as? JsonObject ?: throw IllegalArgumentException()
            require(listOf("workspaces", "tabs", "panes").all { root[it] is JsonArray })
            val snapshot = json.decodeFromString<Snapshot>(payload)
            val workspaceIds = uniqueIds(snapshot.workspaces.map { it.id })
            val tabIds = uniqueIds(snapshot.tabs.map { it.id })
            uniqueIds(snapshot.panes.map { it.id })
            require(snapshot.tabs.all { it.workspaceId in workspaceIds })
            val tabs = snapshot.tabs.associateBy { it.id }
            require(snapshot.panes.all { pane ->
                pane.workspaceId in workspaceIds && pane.tabId in tabIds && tabs[pane.tabId]?.workspaceId == pane.workspaceId
            })
            return snapshot
        } catch (_: Exception) {
            // Parser/require diagnostics may contain pane labels, paths, or supplied IDs.
            throw IllegalArgumentException(ERROR)
        }
    }

    private fun uniqueIds(ids: List<String>): Set<String> {
        require(ids.all(String::isNotBlank))
        return ids.toSet().also { require(it.size == ids.size) }
    }

    /** Reject excessive recursion before parsing unknown nested JSON fields. */
    private fun requireBoundedNesting(payload: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        for (char in payload) {
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 64) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(depth == 0 && !quoted)
    }
}
