package dev.herdr.android.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keep local validation in step with the bridge's InputRequest and body limit. */
internal object InputValidation {
    private val json = Json { explicitNulls = false }

    fun body(text: String?, keys: List<String>): String {
        validate(text, keys)
        return json.encodeToString(PaneInput(text, keys)).also(::validateBody)
    }

    fun validate(text: String?, keys: List<String>) {
        require(!text.isNullOrEmpty() || keys.isNotEmpty()) { "Enter a command or select a key." }
        if (text != null) {
            require(text.codePointCount(0, text.length) <= 16384) { "The command exceeds 16,384 characters." }
            require(text.toByteArray(Charsets.UTF_8).size <= 32768) { "The command exceeds 32 KiB of UTF-8 text." }
            require('\u0000' !in text) { "The command must not contain NUL characters." }
        }
        require(keys.size <= 32) { "Send no more than 32 keys at once." }
        require(keys.all { it.length in 1..32 && it.all { char -> char.code in 33..126 } }) {
            "Keys must be printable logical key names of up to 32 characters."
        }
    }

    fun validateBody(body: String) {
        require(body.toByteArray(Charsets.UTF_8).size <= 65536) { "The encoded input exceeds 64 KiB." }
    }
}
