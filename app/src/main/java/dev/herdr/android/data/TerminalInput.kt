package dev.herdr.android.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.io.IOException

/** Each write is tied to the selected server, pane and particular live observer. */
internal data class TerminalInputTarget(
    val profileId: String,
    val generation: Long,
    val paneId: String,
    val sessionId: Long,
    val observerAttempt: Int,
)

internal class TerminalInput(
    val text: String? = null,
    val keys: List<String> = emptyList(),
    val raw: Boolean = false,
    val acknowledgeDraft: Boolean = false,
) {
    val sizeBytes: Int = (text?.toByteArray(Charsets.UTF_8)?.size ?: 0) + keys.sumOf { it.length }
    override fun toString(): String = "TerminalInput(raw=$raw, redacted)"
}

internal class TerminalInputQueueFullException : IllegalStateException("The terminal input queue is full.")

/**
 * A main-thread-confined, bounded FIFO. A failed or interrupted write is never
 * replayed, and accepted writes waiting behind it are discarded. Raw typing can
 * coalesce while the first request is in flight without crossing a logical key
 * or draft submission. This keeps typing responsive on a slow mobile connection.
 */
internal class TerminalInputQueue<T : Any>(
    private val scope: CoroutineScope,
    private val send: suspend (T, TerminalInput) -> Unit,
    private val onBusy: (T, Boolean) -> Unit = { _, _ -> },
    private val onAcknowledged: (T, TerminalInput) -> Unit = { _, _ -> },
    private val onFailure: (T, Exception) -> Unit = { _, _ -> },
    private val maxRequests: Int = 128,
    private val maxBytes: Int = 128 * 1024,
) {
    data class Discarded(val pending: Boolean, val inFlight: Boolean) {
        val any: Boolean get() = pending || inFlight
    }

    var target: T? = null
        private set
    private var paused = false
    private val pending = ArrayDeque<TerminalInput>()
    private var inFlight: TerminalInput? = null
    private var bytes = 0
    private var worker: Job? = null
    private var epoch = 0L

    fun activate(value: T): Discarded {
        if (target == value) return Discarded(false, false)
        val discarded = clear()
        target = value
        return discarded
    }

    fun clear(): Discarded {
        val discarded = Discarded(pending.isNotEmpty(), inFlight != null)
        val previousTarget = target
        epoch++
        val previousWorker = worker
        worker = null
        target = null
        paused = false
        pending.clear()
        inFlight = null
        bytes = 0
        previousWorker?.cancel()
        if (previousTarget != null) onBusy(previousTarget, false)
        return discarded
    }

    fun resume(value: T) {
        if (target == value) paused = false
    }

    fun offer(value: T, inputs: List<TerminalInput>): Boolean {
        if (value != target || paused || inputs.isEmpty()) return false
        // Apply coalescing to a copy so admission is atomic for an entire paste.
        val admitted = ArrayDeque(pending)
        inputs.forEach { next ->
            val last = admitted.lastOrNull()
            val combined = if (last?.raw == true && next.raw) (last.text ?: "") + (next.text ?: "") else null
            if (combined != null && combined.toByteArray(Charsets.UTF_8).size <= RAW_CHUNK_BYTES) {
                admitted.removeLast()
                admitted.addLast(TerminalInput(text = combined, raw = true))
            } else admitted.addLast(next)
        }
        val addedBytes = inputs.sumOf { it.sizeBytes }
        if (admitted.size + (if (inFlight == null) 0 else 1) > maxRequests || addedBytes > maxBytes - bytes) {
            stopAfterFailure(value, TerminalInputQueueFullException())
            return false
        }
        pending.clear()
        pending.addAll(admitted)
        bytes += addedBytes
        if (worker == null) {
            val writeEpoch = epoch
            val nextWorker = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    while (epoch == writeEpoch && target == value && pending.isNotEmpty()) {
                        val input = pending.removeFirst()
                        inFlight = input
                        send(value, input)
                        if (epoch != writeEpoch || target != value) return@launch
                        inFlight = null
                        bytes -= input.sizeBytes
                        onAcknowledged(value, input)
                    }
                } catch (cancelled: CancellationException) {
                    // A timeout/cancellation not initiated by clear() is also an
                    // uncertain write. Do not leave its remaining input queued.
                    if (epoch == writeEpoch && target == value) {
                        stopAfterFailure(value, IOException("Terminal input was interrupted before delivery was confirmed."))
                    }
                    throw cancelled
                } catch (error: Exception) {
                    if (epoch == writeEpoch && target == value) stopAfterFailure(value, error)
                } finally {
                    if (epoch == writeEpoch && target == value) {
                        worker = null
                        onBusy(value, false)
                    }
                }
            }
            worker = nextWorker
            onBusy(value, true)
            nextWorker.start()
        }
        return true
    }

    private fun stopAfterFailure(value: T, error: Exception) {
        epoch++
        val previousWorker = worker
        worker = null
        pending.clear()
        inFlight = null
        bytes = 0
        paused = true
        previousWorker?.cancel()
        onBusy(value, false)
        onFailure(value, error)
    }

    companion object { const val RAW_CHUNK_BYTES = 4096 }
}

/** Strict, incremental UTF-8; incomplete code points stay on this observer only. */
internal class TerminalInputDecoder {
    internal class Decoded(val text: String, val pending: ByteArray) {
        override fun toString(): String = "DecodedTerminalInput(redacted)"
    }

    private var pending = byteArrayOf()
    val hasPending: Boolean get() = pending.isNotEmpty()

    fun preview(bytes: ByteArray): Decoded {
        require(bytes.size <= 32768) { "Terminal input exceeds 32 KiB. Paste smaller sections." }
        val input = ByteBuffer.wrap(pending + bytes)
        val output = CharBuffer.allocate(input.remaining())
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val result = decoder.decode(input, output, false)
        require(!result.isError) { "Terminal input contains invalid UTF-8. Type or paste the text again." }
        require(result.isUnderflow) { "Terminal input could not be decoded." }
        val tail = ByteArray(input.remaining()).also(input::get)
        require(tail.size <= 3) { "Terminal input contains an incomplete UTF-8 character." }
        output.flip()
        return Decoded(output.toString(), tail)
    }

    fun accept(decoded: Decoded) { pending = decoded.pending.copyOf() }
    fun reset(): Boolean = hasPending.also { pending = byteArrayOf() }

    companion object {
        fun chunks(text: String): List<TerminalInput> {
            val inputs = mutableListOf<TerminalInput>()
            var start = 0
            var offset = 0
            var size = 0
            while (offset < text.length) {
                val codePoint = text.codePointAt(offset)
                val codePointBytes = when { codePoint <= 0x7f -> 1; codePoint <= 0x7ff -> 2; codePoint <= 0xffff -> 3; else -> 4 }
                if (size + codePointBytes > TerminalInputQueue.RAW_CHUNK_BYTES) {
                    inputs.add(TerminalInput(text = text.substring(start, offset), raw = true))
                    start = offset
                    size = 0
                }
                size += codePointBytes
                offset += Character.charCount(codePoint)
            }
            if (offset > start) inputs.add(TerminalInput(text = text.substring(start, offset), raw = true))
            return inputs
        }
    }
}
