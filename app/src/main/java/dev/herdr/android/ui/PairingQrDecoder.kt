package dev.herdr.android.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.nio.ByteBuffer

/** Copies only luminance; CameraX row padding and pixel stride are never interpreted as pixels. */
internal fun copyLuminance(buffer: ByteBuffer, width: Int, height: Int, rowStride: Int, pixelStride: Int): ByteArray {
    require(width in 1..4096 && height in 1..4096 && rowStride > 0 && pixelStride > 0)
    val plane = buffer.duplicate()
    val start = plane.position()
    require(start.toLong() + (height - 1L) * rowStride + (width - 1L) * pixelStride < plane.limit())
    return ByteArray(width * height).also { output ->
        for (row in 0 until height) for (column in 0 until width) {
            output[row * width + column] = plane.get(start + row * rowStride + column * pixelStride)
        }
    }
}

/** Offline QR-only decoding. No image or decoded text leaves this process. */
internal fun decodePairingQr(luminance: ByteArray, width: Int, height: Int): String? {
    val source = PlanarYUVLuminanceSource(luminance, width, height, 0, 0, width, height, false)
    val reader = QRCodeReader()
    val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.CHARACTER_SET to "UTF-8")
    return try {
        reader.decode(BinaryBitmap(HybridBinarizer(source)), hints).text
    } catch (_: ReaderException) {
        try { reader.decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }
        catch (_: ReaderException) { null }
    } finally {
        reader.reset()
    }
}
