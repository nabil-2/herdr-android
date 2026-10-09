package dev.herdr.android.ui

import dev.herdr.android.data.Pane
import dev.herdr.android.data.Snapshot
import java.util.Locale

/** Search spans this server's workspaces; ordinary browsing stays in the selected tab. */
internal fun visibleSessions(snapshot: Snapshot?, workspaceId: String?, tabId: String?, query: String): List<Pane> {
    if (snapshot == null) return emptyList()
    val terms = query.trim().lowercase(Locale.ROOT).split(Regex("\\s+")).filter(String::isNotEmpty)
    if (terms.isEmpty()) return snapshot.panes.filter {
        it.workspaceId == workspaceId && (tabId == null || it.tabId == tabId)
    }
    val workspaces = snapshot.workspaces.associateBy { it.id }
    val tabs = snapshot.tabs.associateBy { it.id }
    return snapshot.panes.filter { pane ->
        val workspace = workspaces[pane.workspaceId]
        val text = listOfNotNull(pane.title, pane.agent, pane.cwd, workspace?.label, workspace?.cwd, tabs[pane.tabId]?.label)
            .joinToString(" ").lowercase(Locale.ROOT)
        terms.all(text::contains)
    }
}
