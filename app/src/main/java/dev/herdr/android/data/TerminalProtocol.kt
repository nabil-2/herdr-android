package dev.herdr.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64

sealed interface TerminalMessage {
    data class Frame(val ansi: ByteArray, val cols: Int, val rows: Int, val full: Boolean) : TerminalMessage
    data class Closed(val reason: String) : TerminalMessage
}

/** Herdr 0.9.3 terminal observer JSON, not a generic terminal protocol. */
object TerminalProtocol {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_MESSAGE_LENGTH = 2 * 1024 * 1024

    fun decode(message: String): TerminalMessage {
        require(message.length <= MAX_MESSAGE_LENGTH) { "The terminal frame is too large." }
        val objectValue = json.parseToJsonElement(message).jsonObject
        return when (objectValue["type"]?.jsonPrimitive?.contentOrNull) {
            "terminal.frame" -> {
                require(objectValue["encoding"]?.jsonPrimitive?.contentOrNull == "ansi") { "Unsupported terminal encoding." }
                val cols = objectValue["width"]?.jsonPrimitive?.intOrNull ?: error("Missing terminal width.")
                val rows = objectValue["height"]?.jsonPrimitive?.intOrNull ?: error("Missing terminal height.")
                require(cols in 20..300 && rows in 5..150) { "Invalid terminal dimensions." }
                val bytes = objectValue["bytes"]?.jsonPrimitive?.contentOrNull ?: error("Missing terminal bytes.")
                val full = objectValue["full"]?.jsonPrimitive?.booleanOrNull ?: error("Missing terminal frame mode.")
                TerminalMessage.Frame(Base64.getDecoder().decode(bytes), cols, rows, full)
            }
            "terminal.closed" -> TerminalMessage.Closed(
                objectValue["reason"]?.jsonPrimitive?.contentOrNull ?: "The terminal session ended.",
            )
            else -> throw IllegalArgumentException("Unsupported terminal message.")
        }
    }
}
