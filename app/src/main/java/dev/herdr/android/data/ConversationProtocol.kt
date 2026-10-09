package dev.herdr.android.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/** Conversation pages are structured Codex records, never parsed terminal output. */
internal object ConversationProtocol {
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val statuses = setOf("idle", "working", "blocked", "done", "unknown")

    fun decode(body: String): ConversationPage {
        try {
            val objectValue = json.parseToJsonElement(body).jsonObject
            for (field in listOf("unchanged", "canSend")) {
                val value = objectValue[field] as? JsonPrimitive
                require(value != null && !value.isString && value.booleanOrNull != null)
            }
            objectValue["messages"]?.jsonArray?.forEach { item ->
                val timestamp = item.jsonObject["timestamp"]
                if (timestamp != null && timestamp.toString() != "null") {
                    require(timestamp is JsonPrimitive && !timestamp.isString && timestamp.longOrNull != null)
                }
            }
            val page = json.decodeFromString<ConversationPage>(body)
            validateOpaque(page.binding, 256)
            validateOpaque(page.revision, 256)
            validateOpaque(page.historyEpoch, 256)
            page.olderCursor?.let { validateOpaque(it, 2048) }
            require(page.messages.size <= 50 && page.messages.map { it.id }.distinct().size == page.messages.size)
            require(!page.unchanged || page.messages.isEmpty())
            require(page.status in statuses)
            require(!page.canSend || page.status in setOf("idle", "done"))
            require(page.warning == null || page.warning.length <= 1000)
            require(page.canSendReason == null || page.canSendReason.length <= 1000)
            page.messages.forEach { message ->
                require(message.id.isNotBlank() && message.id.length <= 512 && message.id.none { it.code < 32 || it.code == 127 })
                require(message.role in setOf("user", "assistant"))
                require(message.phase == null || message.phase in setOf("commentary", "final_answer"))
                require(message.text.length <= 256 * 1024)
                require(message.timestamp == null || message.timestamp in 0..253402300799L)
                require(message.timestampSource == null || message.timestampSource in setOf("turn_start", "turn_end"))
                require(message.timestampSource == null || message.timestamp != null)
            }
            return page
        } catch (_: Exception) {
            throw IOException("The bridge returned an invalid conversation page.")
        }
    }

    fun input(binding: String, text: String): String {
        validateOpaque(binding, 256)
        require(text.isNotBlank()) { "Enter a message before sending." }
        require(text.none { (it.code < 32 && it !in "\n\r\t") || it.code == 127 }) {
            "Chat messages cannot contain terminal control characters. Use Terminal for interactive input."
        }
        InputValidation.validate(text, emptyList())
        return json.encodeToString(ChatInput(binding, text)).also(InputValidation::validateBody)
    }

    fun acknowledgement(body: String): String? {
        try {
            val value = json.parseToJsonElement(body).jsonObject
            val ok = value["ok"] as? JsonPrimitive
            require(ok != null && !ok.isString && ok.booleanOrNull == true)
            val warning = value["warning"]
            if (warning == null || warning.toString() == "null") return null
            require(warning is JsonPrimitive && warning.isString && warning.content.length <= 1000)
            return warning.content
        } catch (_: Exception) {
            throw IOException("Message delivery was not acknowledged. Check the conversation before sending again.")
        }
    }

    fun validateOpaque(value: String, maxLength: Int) {
        require(value.isNotEmpty() && value.length <= maxLength && value.all { it.code in 33..126 }) {
            "The conversation identifier is invalid. Refresh the conversation."
        }
    }
}

/** Pure transitions shared by the ViewModel and tests, including draft acknowledgement. */
internal object ChatState {
    fun supportsPaneChat(pane: Pane, capabilities: List<String>): Boolean = "codex-chat" in capabilities &&
        pane.conversation?.let { it.provider == "codex" && it.available } == true

    fun requiresFullPage(current: ChatContent, page: ConversationPage): Boolean = page.unchanged &&
        (current.bindingId != page.binding || current.revision != page.revision || current.historyEpoch != page.historyEpoch)

    fun latest(current: ChatContent, page: ConversationPage): ChatContent {
        val sameBinding = current.bindingId == page.binding
        if (requiresFullPage(current, page)) {
            throw IOException("The bridge returned an inconsistent conversation revision.")
        }
        val initial = when {
            !sameBinding -> ChatContent(ackRevision = current.ackRevision)
            current.historyEpoch != page.historyEpoch -> current.copy(messages = emptyList(), olderCursor = null, loadingOlder = false)
            else -> current
        }
        val ids = page.messages.map { it.id }.toSet()
        val messages = if (page.unchanged) initial.messages else initial.messages.filterNot { it.id in ids } + page.messages
        return initial.copy(
            bindingId = page.binding,
            revision = page.revision,
            historyEpoch = page.historyEpoch,
            messages = messages,
            olderCursor = if (page.unchanged) initial.olderCursor else page.olderCursor,
            loading = false,
            refreshing = false,
            canSend = page.canSend,
            canSendReason = page.canSendReason,
            status = page.status,
            warning = page.warning,
            error = if (initial.deliveryUncertain) initial.error else null,
        )
    }

    fun older(current: ChatContent, page: ConversationPage): ChatContent {
        require(current.bindingId == page.binding && current.revision == page.revision && current.historyEpoch == page.historyEpoch && !page.unchanged) {
            "The conversation changed. Refresh before loading older messages."
        }
        val ids = page.messages.map { it.id }.toSet()
        return current.copy(
            messages = page.messages + current.messages.filterNot { it.id in ids },
            olderCursor = page.olderCursor,
            loadingOlder = false,
            error = if (current.deliveryUncertain) current.error else null,
        )
    }

    fun acknowledged(current: ChatContent, binding: String, submitted: String, warning: String?): ChatContent {
        if (current.bindingId != binding) return current
        return current.copy(
            draft = if (current.draft == submitted) "" else current.draft,
            sending = false,
            canSend = false, // The next authoritative read confirms readiness again.
            canSendReason = "Waiting for the agent to receive your message.",
            ackRevision = current.ackRevision + 1,
            warning = warning ?: current.warning,
            error = null,
            deliveryUncertain = false,
        )
    }
}
