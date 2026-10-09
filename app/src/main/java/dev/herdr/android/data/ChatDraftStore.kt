package dev.herdr.android.data

/** Memory-only drafts; an authoritative binding must be read before restoring one. */
internal class ChatDraftStore(private val maxEntries: Int = 16, private val maxBytes: Int = 512 * 1024) {
    private data class Key(val profileId: String, val paneId: String, val binding: String)
    private data class Draft(val text: String, val uncertain: Boolean, val error: String?, val bytes: Int)
    private val drafts = LinkedHashMap<Key, Draft>(16, 0.75f, true)

    fun remember(profileId: String, paneId: String, chat: ChatContent) {
        val binding = chat.bindingId ?: return
        val key = Key(profileId, paneId, binding)
        // Switching bindings can cancel the request after it reached the host.
        // Restoring a pending request must never make its draft look unsent.
        val uncertain = chat.deliveryUncertain || chat.sending
        if (chat.draft.isEmpty() && !uncertain) {
            drafts.remove(key)
            return
        }
        val bytes = chat.draft.toByteArray(Charsets.UTF_8).size
        require(bytes <= MAX_DRAFT_BYTES)
        val error = if (uncertain) chat.error ?: "Message delivery was not confirmed. Check the conversation before sending again." else null
        drafts[key] = Draft(chat.draft, uncertain, error, bytes + (error?.length ?: 0) * 2)
        while (drafts.size > maxEntries || drafts.values.sumOf { it.bytes } > maxBytes) {
            drafts.remove(drafts.keys.first())
        }
    }

    fun restore(profileId: String, paneId: String, confirmed: ChatContent): ChatContent {
        val binding = confirmed.bindingId ?: return confirmed
        val draft = drafts[Key(profileId, paneId, binding)] ?: return confirmed
        return confirmed.copy(draft = draft.text, deliveryUncertain = draft.uncertain, error = draft.error ?: confirmed.error)
    }

    fun removeProfile(profileId: String) {
        drafts.keys.removeAll { it.profileId == profileId }
    }

    companion object { const val MAX_DRAFT_BYTES = 64 * 1024 }
}
