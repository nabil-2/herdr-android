package dev.herdr.android.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException

@Serializable
data class CodexConversationChoice(val id: String, val title: String, val createdAt: Long, val status: String)

@Serializable
internal data class CodexConversationChoices(val conversations: List<CodexConversationChoice>, val selectionToken: String)

@Serializable
private data class CodexConversationSelectionInput(val id: String, val selectionToken: String)

data class CodexConversationSelectionState(
    val profileId: String? = null,
    val paneId: String? = null,
    val showing: Boolean = false,
    val loading: Boolean = false,
    val selecting: Boolean = false,
    val choices: List<CodexConversationChoice> = emptyList(),
    val selectionToken: String? = null,
    val error: String? = null,
)

internal object CodexConversationSelectionProtocol {
    private val json = Json { ignoreUnknownKeys = true }
    private val idPattern = Regex("^[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}$")
    private val tokenPattern = Regex("^[A-Za-z0-9_-]{43}$")

    fun decode(body: String): CodexConversationChoices = try {
        val raw = json.parseToJsonElement(body).jsonObject
        raw.getValue("conversations").jsonArray.forEach { choice ->
            val time = choice.jsonObject["createdAt"] as? JsonPrimitive
            require(time != null && !time.isString && time.longOrNull != null)
        }
        json.decodeFromString<CodexConversationChoices>(body).also { result ->
            require(tokenPattern.matches(result.selectionToken))
            require(result.conversations.size <= 32 && result.conversations.map { it.id }.distinct().size == result.conversations.size)
            result.conversations.forEach { choice ->
                require(idPattern.matches(choice.id) && choice.createdAt in 0..253402300799L)
                require(choice.title.isNotBlank() && choice.title.length <= 512 && choice.title.none { it.code < 32 || it.code == 127 })
                require(choice.status in setOf("idle", "working", "blocked", "done", "unknown"))
            }
        }
    } catch (_: Exception) {
        throw IOException("The server returned an invalid conversation list. Refresh and try again.")
    }

    fun input(id: String, token: String): String {
        require(idPattern.matches(id) && tokenPattern.matches(token)) { "Refresh the conversation list before choosing a chat." }
        return json.encodeToString(CodexConversationSelectionInput(id, token))
    }
}
