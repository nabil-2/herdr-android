package dev.herdr.android.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertThrows
import org.junit.Test

class InputValidationTest {
    @Test fun `valid pasted Unicode respects both character and UTF8 byte limits`() {
        InputValidation.validate("a".repeat(16384), listOf("enter"))
        InputValidation.validate("a".repeat(16383) + "😀", emptyList())
        InputValidation.validate("😀".repeat(8192), listOf("enter"))
        assertThrows(IllegalArgumentException::class.java) { InputValidation.validate("a".repeat(16385), listOf("enter")) }
        assertThrows(IllegalArgumentException::class.java) { InputValidation.validate("😀".repeat(8193), listOf("enter")) }
        assertThrows(IllegalArgumentException::class.java) { InputValidation.validate("雪".repeat(10923), listOf("enter")) }
    }

    @Test fun `NUL and invalid key batches are rejected before delivery`() {
        assertThrows(IllegalArgumentException::class.java) { InputValidation.validate("a\u0000b", listOf("enter")) }
        assertThrows(IllegalArgumentException::class.java) { InputValidation.validate(null, List(33) { "enter" }) }
        listOf("", "two words", "\n", "é", "x".repeat(33)).forEach {
            assertThrows(IllegalArgumentException::class.java) { InputValidation.validate(null, listOf(it)) }
        }
        InputValidation.validate(null, listOf("enter", "esc", "up", "ctrl+c"))
    }

    @Test fun `JSON escape expansion cannot exceed the bridge body cap`() {
        val text = "\u0001".repeat(16384)
        InputValidation.validate(text, listOf("enter"))
        val body = Json.encodeToString(PaneInput(text, listOf("enter")))
        assertThrows(IllegalArgumentException::class.java) { InputValidation.validateBody(body) }
    }
}
