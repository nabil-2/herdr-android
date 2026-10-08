package dev.herdr.android.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
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
}
