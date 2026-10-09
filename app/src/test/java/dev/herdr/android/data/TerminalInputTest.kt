package dev.herdr.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class TerminalInputTest {
    private val target = TerminalInputTarget("profile-a", 1, "w1:p1", 10, 0)

    @Test fun `slow mobile writes keep Unicode keys and commands in order without dropping typing`() = runTest {
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<TerminalInput>()
        var draftAcknowledgements = 0
        val queue = TerminalInputQueue<TerminalInputTarget>(this,
            send = { _, input -> sent.add(input); if (sent.size == 1) release.await() },
            onAcknowledged = { _, input -> if (input.acknowledgeDraft) draftAcknowledgements++ },
        )
        queue.activate(target)
        assertTrue(queue.offer(target, listOf(TerminalInput(text = "雪", raw = true))))
        runCurrent()
        assertTrue(queue.offer(target, listOf(TerminalInput(text = "😀", raw = true))))
        assertTrue(queue.offer(target, listOf(TerminalInput(text = "b", raw = true))))
        assertTrue(queue.offer(target, listOf(TerminalInput(keys = listOf("left")))))
        assertTrue(queue.offer(target, listOf(TerminalInput("echo done", listOf("enter"), acknowledgeDraft = true))))
        assertTrue(queue.offer(target, listOf(TerminalInput(text = "\u0003\u001b[A", raw = true))))
        assertEquals(1, sent.size)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("雪", "😀b", null, "echo done", "\u0003\u001b[A"), sent.map { it.text })
        assertEquals(listOf("left"), sent[2].keys)
        assertEquals(listOf("enter"), sent[3].keys)
        assertEquals(1, draftAcknowledgements)
    }

    @Test fun `switching any part of the target cancels pending input and ignores stale acknowledgement`() = runTest {
        val replacements = listOf(
            target.copy(profileId = "profile-b"), target.copy(generation = 2),
            target.copy(paneId = "w1:p2"), target.copy(sessionId = 11), target.copy(observerAttempt = 1),
        )
        replacements.forEach { replacement ->
            val release = CompletableDeferred<Unit>()
            val sent = mutableListOf<Pair<TerminalInputTarget, String?>>()
            val acknowledged = mutableListOf<TerminalInputTarget>()
            val queue = TerminalInputQueue<TerminalInputTarget>(this,
                send = { value, input -> sent.add(value to input.text); if (value == target) release.await() },
                onAcknowledged = { value, _ -> acknowledged.add(value) },
            )
            queue.activate(target)
            queue.offer(target, listOf(TerminalInput("old in flight")))
            runCurrent()
            queue.offer(target, listOf(TerminalInput("old pending")))
            val discarded = queue.activate(replacement)
            assertTrue(discarded.inFlight)
            assertTrue(discarded.pending)
            assertFalse(queue.offer(target, listOf(TerminalInput("stale"))))
            assertTrue(queue.offer(replacement, listOf(TerminalInput("new"))))
            release.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf(target to "old in flight", replacement to "new"), sent)
            assertEquals(listOf(replacement), acknowledged)
            queue.clear()
        }
    }

    @Test fun `failed delivery clears followers pauses input and requires explicit resume without replay`() = runTest {
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<String?>()
        val failures = mutableListOf<Exception>()
        val queue = TerminalInputQueue<TerminalInputTarget>(this,
            send = { _, input ->
                sent.add(input.text)
                if (sent.size == 1) { release.await(); throw IOException("Acknowledgement lost") }
            },
            onFailure = { _, error -> failures.add(error) },
        )
        queue.activate(target)
        queue.offer(target, listOf(TerminalInput("maybe delivered")))
        runCurrent()
        queue.offer(target, listOf(TerminalInput("must not run")))
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("maybe delivered"), sent)
        assertEquals(1, failures.size)
        assertFalse(queue.offer(target, listOf(TerminalInput("refused"))))
        queue.activate(target) // More observer frames must not clear uncertainty.
        assertFalse(queue.offer(target, listOf(TerminalInput("also refused"))))
        queue.resume(target)
        assertTrue(queue.offer(target, listOf(TerminalInput("new intentional input"))))
        advanceUntilIdle()
        assertEquals(listOf("maybe delivered", "new intentional input"), sent)
    }

    @Test fun `capacity includes in-flight writes and full queue stops with an explicit refusal`() = runTest {
        val release = CompletableDeferred<Unit>()
        val sent = mutableListOf<String?>()
        val failures = mutableListOf<Exception>()
        val queue = TerminalInputQueue<TerminalInputTarget>(this,
            send = { _, input -> sent.add(input.text); release.await() },
            onFailure = { _, error -> failures.add(error) }, maxRequests = 2,
        )
        queue.activate(target)
        assertTrue(queue.offer(target, listOf(TerminalInput("first"))))
        runCurrent()
        assertTrue(queue.offer(target, listOf(TerminalInput("second"))))
        assertFalse(queue.offer(target, listOf(TerminalInput("third"))))
        assertTrue(failures.single() is TerminalInputQueueFullException)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("first"), sent)
        assertFalse(queue.offer(target, listOf(TerminalInput("fourth"))))
    }

    @Test fun `a large paste is admitted atomically and byte capacity also includes the current request`() = runTest {
        val release = CompletableDeferred<Unit>()
        val failures = mutableListOf<Exception>()
        val sent = mutableListOf<String?>()
        val queue = TerminalInputQueue<TerminalInputTarget>(this,
            send = { _, input -> sent.add(input.text); release.await() },
            onFailure = { _, error -> failures.add(error) }, maxBytes = 5,
        )
        queue.activate(target)
        assertTrue(queue.offer(target, listOf(TerminalInput("雪")))) // Three UTF-8 bytes.
        runCurrent()
        assertFalse(queue.offer(target, listOf(TerminalInput("ab"), TerminalInput("c"))))
        assertEquals(1, failures.size)
        release.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf("雪"), sent)
    }

    @Test fun `background or disconnect before dispatch discards local input without claiming remote delivery`() = runTest {
        val sent = mutableListOf<TerminalInput>()
        val queue = TerminalInputQueue<TerminalInputTarget>(this, send = { _, input -> sent.add(input) })
        queue.activate(target)
        queue.offer(target, listOf(TerminalInput("local only")))
        val discarded = queue.clear()
        assertTrue(discarded.pending)
        assertFalse(discarded.inFlight)
        assertFalse(queue.offer(target, listOf(TerminalInput("disconnected"))))
        advanceUntilIdle()
        assertTrue(sent.isEmpty())
    }

    @Test fun `timeout cancellation also flushes pending input and blocks further typing`() = runTest {
        val failures = mutableListOf<Exception>()
        val sent = mutableListOf<TerminalInput>()
        val queue = TerminalInputQueue<TerminalInputTarget>(this,
            send = { _, input -> sent.add(input); throw CancellationException("Timed out") },
            onFailure = { _, error -> failures.add(error) },
        )
        queue.activate(target)
        queue.offer(target, listOf(TerminalInput("first"), TerminalInput("second")))
        advanceUntilIdle()
        assertEquals(1, sent.size)
        assertEquals(1, failures.size)
        assertFalse(queue.offer(target, listOf(TerminalInput("third"))))
    }

    @Test fun `UTF8 split at every byte boundary is preserved with controls and no replacement characters`() {
        val original = "a雪😀\u0000\u0003\r\n\u001b[A"
        val decoder = TerminalInputDecoder()
        val result = StringBuilder()
        original.toByteArray(Charsets.UTF_8).forEach { byte ->
            val decoded = decoder.preview(byteArrayOf(byte))
            result.append(decoded.text)
            decoder.accept(decoded)
        }
        assertEquals(original, result.toString())
        assertFalse(decoder.hasPending)
    }

    @Test fun `invalid UTF8 is refused without consuming the previous fragment`() {
        val decoder = TerminalInputDecoder()
        val prefix = decoder.preview(byteArrayOf(0xf0.toByte(), 0x9f.toByte()))
        decoder.accept(prefix)
        assertTrue(decoder.hasPending)
        assertThrows(IllegalArgumentException::class.java) { decoder.preview(byteArrayOf('A'.code.toByte())) }
        val completed = decoder.preview(byteArrayOf(0x98.toByte(), 0x80.toByte()))
        assertEquals("😀", completed.text)
        decoder.accept(completed)
        assertFalse(decoder.hasPending)
        listOf(byteArrayOf(0xc0.toByte(), 0x80.toByte()), byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte())).forEach {
            assertThrows(IllegalArgumentException::class.java) { decoder.preview(it) }
        }
    }

    @Test fun `switching observer clears incomplete Unicode instead of carrying it to another pane`() {
        val decoder = TerminalInputDecoder()
        decoder.accept(decoder.preview(byteArrayOf(0xe9.toByte())))
        assertTrue(decoder.reset())
        assertFalse(decoder.hasPending)
        assertThrows(IllegalArgumentException::class.java) { decoder.preview(byteArrayOf(0x9b.toByte(), 0xaa.toByte())) }
        assertEquals("new", decoder.preview("new".toByteArray()).text)
        assertThrows(IllegalArgumentException::class.java) { decoder.preview(ByteArray(32769) { 'a'.code.toByte() }) }
    }

    @Test fun `paste chunks retain emoji boundaries and stay below JSON request body limits`() {
        val original = ("a😀雪\u0000\u001b".repeat(2000))
        val inputs = TerminalInputDecoder.chunks(original)
        assertEquals(original, inputs.joinToString("") { it.text.orEmpty() })
        assertArrayEquals(original.toByteArray(Charsets.UTF_8), inputs.flatMap { it.text!!.toByteArray(Charsets.UTF_8).asIterable() }.toByteArray())
        inputs.forEach { input ->
            assertTrue(input.raw)
            assertTrue(input.sizeBytes <= TerminalInputQueue.RAW_CHUNK_BYTES)
            assertFalse(input.text!!.last().isHighSurrogate())
            InputValidation.validateBody(kotlinx.serialization.json.Json.encodeToString(RawTerminalInput.serializer(), RawTerminalInput(input.text)))
        }
    }

    @Test fun `debug formatting never exposes typed terminal text`() {
        assertFalse(TerminalInput("secret command").toString().contains("secret"))
        assertFalse(RawTerminalInput("secret command").toString().contains("secret"))
        assertFalse(TerminalInputDecoder().preview("secret command".toByteArray()).toString().contains("secret"))
    }
}
