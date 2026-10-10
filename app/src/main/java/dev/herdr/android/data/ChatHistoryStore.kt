package dev.herdr.android.data

/** Bounded, memory-only transcripts let pane changes detect an unexpected rebind. */
internal class ChatHistoryStore(private val maxEntries: Int = 16, private val maxCharacters: Int = 4 * 1024 * 1024) {
    private data class Key(val profileId: String, val paneId: String)
    private data class Entry(val chat: ChatContent, val characters: Int)
    private val histories = LinkedHashMap<Key, Entry>(16, 0.75f, true)

    fun remember(profileId: String, paneId: String, chat: ChatContent) {
        if (chat.bindingId == null) return
        val key = Key(profileId, paneId)
        val originalCost = chat.messages.sumOf { it.text.length.toLong() + it.id.length + 96 }
        var cost = 0
        val recent = ArrayList<ChatMessage>()
        for (message in chat.messages.asReversed()) {
            val messageCost = message.text.length.toLong() + message.id.length + 96
            if (messageCost > maxCharacters - cost) break
            recent.add(message)
            cost += messageCost.toInt()
        }
        // Drafts and write uncertainty have their separate binding-scoped store.
        // Local attachment files/receipts must never survive selection changes.
        histories[key] = Entry(chat.copy(
            messages = recent.asReversed().toList(),
            revision = if (originalCost > maxCharacters) null else chat.revision,
            olderCursor = if (originalCost > maxCharacters) null else chat.olderCursor,
            draft = "", attachments = emptyList(), attachmentError = null,
            loading = false, refreshing = false, loadingOlder = false,
            sending = false, canSend = false,
        ), cost)
        while (histories.size > maxEntries || histories.values.sumOf { it.characters.toLong() } > maxCharacters) {
            histories.remove(histories.keys.first())
        }
    }

    fun restore(profileId: String, paneId: String): ChatContent? = histories[Key(profileId, paneId)]?.chat

    fun remove(profileId: String, paneId: String) { histories.remove(Key(profileId, paneId)) }

    fun removeProfile(profileId: String) { histories.keys.removeAll { it.profileId == profileId } }
}
