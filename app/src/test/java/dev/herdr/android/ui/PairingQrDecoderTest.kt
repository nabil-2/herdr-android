package dev.herdr.android.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.nio.ByteBuffer

class PairingQrDecoderTest {
    private val payload = """{"type":"herdr-pocket-pair","version":1,"url":"https://dust.example.ts.net","name":"Dust","code":"${"A".repeat(43)}","expiresAt":9223372036854775807}"""

    @Test
    fun cameraPlanesWithPaddingPixelStrideAndBufferOffsetDecodeOffline() {
        val width = 320
        val height = 320
        val qr = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, width, height)
        val pixelStride = 2
        val rowStride = width * pixelStride + 12
        val offset = 7
        val plane = ByteArray(offset + height * rowStride) { 77 }
        for (row in 0 until height) for (column in 0 until width) {
            plane[offset + row * rowStride + column * pixelStride] = if (qr[column, row]) 0 else 255.toByte()
        }
        val buffer = ByteBuffer.wrap(plane).apply { position(offset) }
        val luminance = copyLuminance(buffer, width, height, rowStride, pixelStride)
        assertEquals(offset, buffer.position()) // Reading must not mutate CameraX's buffer.
        assertEquals(payload, decodePairingQr(luminance, width, height))
    }

    @Test
    fun invertedPairingCodesDecodeAndBlankCameraFramesDoNot() {
        val width = 320
        val qr = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, width, width)
        val inverted = ByteArray(width * width) { index -> if (qr[index % width, index / width]) 255.toByte() else 0 }
        assertEquals(payload, decodePairingQr(inverted, width, width))
        assertNull(decodePairingQr(ByteArray(96 * 96) { 255.toByte() }, 96, 96))
    }

    @Test
    fun fullColorScreenshotDecodesQrInsideSurroundingInterfaceAndAfterRotation() {
        val width = 1080
        val height = 1920
        val pixels = IntArray(width * height) { index ->
            when {
                index / width < 240 -> 0xff192532.toInt()
                index / width > 1550 -> 0xffbadbff.toInt()
                else -> 0xfff4f6fa.toInt()
            }
        }
        val qrSize = 480
        val qr = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, qrSize, qrSize)
        for (row in 0 until qrSize) for (column in 0 until qrSize) {
            pixels[(row + 690) * width + column + 300] = if (qr[column, row]) 0xff142639.toInt() else 0xffffffff.toInt()
        }
        assertEquals(payload, decodePairingQrPixels(pixels, width, height))
        val rotated = IntArray(pixels.size)
        for (row in 0 until height) for (column in 0 until width) {
            rotated[column * height + height - row - 1] = pixels[row * width + column]
        }
        assertEquals(payload, decodePairingQrPixels(rotated, height, width))
        assertNull(decodePairingQrPixels(IntArray(320 * 480) { 0xffbadbff.toInt() }, 320, 480))
    }

    @Test
    fun oversizedAndMalformedImagesAreRejectedBeforePixelAllocation() {
        assertEquals(1, pairingImageSampleSize(1080, 1920))
        assertEquals(4, pairingImageSampleSize(4320, 7680))
        assertEquals(16, pairingImageSampleSize(32768, 32768))
        assertThrows(RuntimeException::class.java) { pairingImageSampleSize(-1, -1) }
        assertThrows(RuntimeException::class.java) { pairingImageSampleSize(100_000, 100_000) }
        assertEquals(listOf<Byte>(1, 2, 3), readBoundedPairingImage(ByteArrayInputStream(byteArrayOf(1, 2, 3))).toList())
        val unbounded = object : InputStream() {
            override fun read(): Int = 0
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = length
        }
        assertThrows(RuntimeException::class.java) { readBoundedPairingImage(unbounded) }
    }
}
