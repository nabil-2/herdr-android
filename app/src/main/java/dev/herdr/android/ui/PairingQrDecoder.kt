package dev.herdr.android.ui

import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
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
    return decodeQrSource(source)
}

/** Screenshots may have any orientation and surrounding UI that obscures QR detection. */
internal fun decodePairingQrPixels(pixels: IntArray, width: Int, height: Int): String? {
    require(width in 1..2048 && height in 1..2048 && pixels.size == width * height)
    var source: LuminanceSource = RGBLuminanceSource(width, height, pixels)
    repeat(4) { orientation ->
        decodeScreenshotSource(source)?.let { return it }
        if (orientation < 3) source = rotateScreenshotSource(source)
    }
    return null
}

private fun decodeScreenshotSource(source: LuminanceSource): String? {
    decodeQrSource(source)?.let { return it }
    val cropWidth = (source.width * 3 / 4).coerceAtLeast(1)
    val cropHeight = (source.height * 3 / 4).coerceAtLeast(1)
    for (top in listOf(0, source.height - cropHeight)) for (left in listOf(0, source.width - cropWidth)) {
        decodeQrSource(source.crop(left, top, cropWidth, cropHeight))?.let { return it }
    }
    return null
}

private fun rotateScreenshotSource(source: LuminanceSource): LuminanceSource {
    val original = source.matrix
    val rotated = ByteArray(original.size)
    for (row in 0 until source.height) for (column in 0 until source.width) {
        rotated[(source.width - column - 1) * source.height + row] = original[row * source.width + column]
    }
    return PlanarYUVLuminanceSource(rotated, source.height, source.width, 0, 0, source.height, source.width, false)
}

private fun decodeQrSource(source: LuminanceSource): String? {
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
