package dev.herdr.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.io.IOException

/** Validate receipts before they can be referenced by an agent message. */
internal object ChatAttachmentProtocol {
    const val MAX_FILE_BYTES = 16L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 32L * 1024 * 1024
    const val MAX_FILES = 8
    private val idPattern = Regex("^[a-f0-9]{32}$")
    private val json = Json { ignoreUnknownKeys = true }

    fun validateIds(ids: List<String>) {
        require(ids.size <= MAX_FILES && ids.distinct().size == ids.size && ids.all { idPattern.matches(it) }) {
            "The attached files changed. Remove them and attach them again."
        }
    }

    fun validateName(name: String) {
        require(name.isNotBlank() && name !in setOf(".", "..") && name.codePointCount(0, name.length) <= 160 &&
            name.toByteArray(Charsets.UTF_8).size <= 640 && name.none { it == '/' || it == '\\' || it.code < 32 || it.code == 127 }) {
            "This filename is unsupported. Rename the file before attaching it."
        }
    }

    fun decode(body: String, name: String, kind: ChatAttachmentKind, size: Long): UploadedChatAttachment = try {
        val sizeValue = json.parseToJsonElement(body).jsonObject["sizeBytes"] as? JsonPrimitive
        require(sizeValue != null && !sizeValue.isString && sizeValue.longOrNull != null)
        json.decodeFromString<UploadedChatAttachment>(body).also {
            validateIds(listOf(it.id))
            validateName(it.name)
            require(it.name == name && it.kind == kind.wireName && it.sizeBytes == size && size in 1..MAX_FILE_BYTES)
            require(it.mimeType.isNotBlank() && it.mimeType.length <= 100 && it.mimeType.none { c -> c.code < 32 || c.code == 127 })
            if (kind == ChatAttachmentKind.IMAGE) require(it.mimeType in setOf("image/png", "image/jpeg", "image/webp"))
        }
    } catch (_: Exception) {
        throw IOException("The server did not confirm this attachment. Remove it and attach it again.")
    }
}
