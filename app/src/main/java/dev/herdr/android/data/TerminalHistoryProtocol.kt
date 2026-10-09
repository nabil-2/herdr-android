package dev.herdr.android.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** A history response cannot change the pane it belongs to or grow without bound. */
internal object TerminalHistoryProtocol {
    fun decode(payload: String, paneId: String): TerminalHistory {
        val value = try { Json.parseToJsonElement(payload).jsonObject }
        catch (_: Exception) { throw IllegalArgumentException("The server returned invalid terminal history.") }
        val responsePane = value["paneId"] as? JsonPrimitive
        val text = value["text"] as? JsonPrimitive
        val truncated = value["truncated"] as? JsonPrimitive
        require(responsePane?.isString == true && responsePane.content == paneId && text?.isString == true &&
            truncated != null && !truncated.isString && truncated.booleanOrNull != null) {
            "The server returned terminal history for an invalid pane or format."
        }
        val output = text.content
        val bytes = try {
            Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(output)).remaining()
        } catch (_: Exception) { throw IllegalArgumentException("The server returned invalid terminal history text.") }
        val lines = output.count { it == '\n' } + if (output.isNotEmpty() && !output.endsWith('\n')) 1 else 0
        require(bytes <= 65536 && lines <= 1000) { "The server returned too much terminal history." }
        return TerminalHistory(paneId, output, truncated.booleanOrNull!!)
    }
}
