package dev.herdr.android.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.IOException

/** A creation acknowledgement must identify a new pane inside the requested workspace. */
internal object TabCreationProtocol {
    const val CODEX_CREATION_REQUIREMENT = "New Codex chats require companion 0.7.1 on a Linux host. Update the companion or use Terminal."
    fun supportsIsolatedCodex(capabilities: List<String>): Boolean =
        "codex-create" in capabilities && "codex-create-isolated" in capabilities

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val workspaceId = Regex("w[1-9][0-9]*")

    fun input(workspace: String, label: String, cwd: String, codex: Boolean): String {
        require(workspaceId.matches(workspace) && workspace.length <= 96) { "Select a valid workspace." }
        require(label.none { it.code < 32 || it.code == 127 } && cwd.none { it.code < 32 || it.code == 127 }) {
            "Tab names and directories cannot contain control characters."
        }
        val name = label.trim()
        val directory = cwd.trim()
        require(name.isNotBlank() && name.length <= 100 && name.none { it.code < 32 || it.code == 127 }) {
            "Enter a tab name (up to 100 characters)."
        }
        require(directory.length <= 4096 && directory.none { it.code < 32 || it.code == 127 } &&
            (directory.startsWith("/") || directory.startsWith("~/") || directory == "~")) {
            "Enter an absolute directory on the server, or use ~/project."
        }
        return json.encodeToString(TabInput(if (codex) "codex" else "terminal", name, directory))
    }

    fun decode(body: String, workspace: String): CreatedTab {
        try {
            val created = json.decodeFromString<CreatedTab>(body)
            require(workspaceId.matches(workspace) && created.workspaceId == workspace)
            require(created.tabId.length <= 96 && Regex("${Regex.escape(workspace)}:t[1-9][0-9]*").matches(created.tabId))
            require(created.paneId.length <= 96 && Regex("${Regex.escape(workspace)}:p[1-9][0-9]*").matches(created.paneId))
            require(created.warning == null || (created.warning.length <= 1000 && created.warning.none { it.code < 32 || it.code == 127 }))
            return created
        } catch (_: Exception) {
            throw IOException("The server did not confirm the new tab. Refresh workspaces before trying again.")
        }
    }
}
