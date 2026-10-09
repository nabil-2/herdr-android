package dev.herdr.android.ui

import android.content.ContentResolver
import android.content.ActivityNotFoundException
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream

internal sealed interface PairingImageResult {
    data class Code(val value: String) : PairingImageResult {
        override fun toString(): String = "PairingImageResult.Code(redacted)"
    }
    data class Error(val message: String) : PairingImageResult
    data object Cancelled : PairingImageResult
}

/** Uses the system photo picker, with its document-picker fallback, and needs no media permission. */
@Composable
internal fun rememberPairingImagePicker(onReading: () -> Unit, onResult: (PairingImageResult) -> Unit): () -> Unit {
    val resolver = LocalContext.current.contentResolver
    val scope = rememberCoroutineScope()
    val currentResult = rememberUpdatedState(onResult)
    val currentReading = rememberUpdatedState(onReading)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) currentResult.value(PairingImageResult.Cancelled)
        else {
            currentReading.value()
            scope.launch {
                val result = withContext(Dispatchers.IO) { readPairingImage(resolver, uri) }
                currentResult.value(result)
            }
        }
    }
    return {
        try {
            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        } catch (_: ActivityNotFoundException) {
            currentResult.value(PairingImageResult.Error("The image picker is unavailable. Paste the pairing code instead."))
        }
    }
}

/** No image, URI grant, or decoded invitation is persisted or sent to a server. */
internal fun readPairingImage(resolver: ContentResolver, uri: Uri): PairingImageResult = try {
    val encoded = resolver.openInputStream(uri)?.use(::readBoundedPairingImage)
        ?: throw PairingImageException("This image could not be opened. Choose another screenshot.")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(encoded, 0, encoded.size, bounds)
    val options = BitmapFactory.Options().apply {
        inSampleSize = pairingImageSampleSize(bounds.outWidth, bounds.outHeight)
        inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
    }
    val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size, options)
        ?: throw PairingImageException("This image format could not be read. Choose a PNG or JPEG screenshot.")
    val code = try {
        if (bitmap.width > 2048 || bitmap.height > 2048) {
            throw PairingImageException("This image is too large. Crop it around the pairing code and try again.")
        }
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        decodePairingQrPixels(pixels, bitmap.width, bitmap.height)
    } finally {
        bitmap.recycle()
    }
    if (code == null) PairingImageResult.Error("No QR code was found. Choose a clear screenshot, or crop it around the pairing code.")
    else PairingImageResult.Code(code)
} catch (failure: CancellationException) {
    throw failure
} catch (failure: PairingImageException) {
    PairingImageResult.Error(failure.message ?: "This screenshot could not be read.")
} catch (_: Exception) {
    PairingImageResult.Error("This image could not be opened. Choose another screenshot.")
} catch (_: OutOfMemoryError) {
    PairingImageResult.Error("This image needs too much memory. Crop it around the pairing code and try again.")
}

private class PairingImageException(message: String) : RuntimeException(message)

internal fun pairingImageSampleSize(width: Int, height: Int): Int {
    if (width !in 1..32768 || height !in 1..32768) {
        throw PairingImageException("This image format or size is unsupported. Choose a PNG or JPEG screenshot.")
    }
    var sample = 1
    while ((width + sample - 1) / sample > 2048 || (height + sample - 1) / sample > 2048) sample *= 2
    return sample
}

internal fun readBoundedPairingImage(input: InputStream): ByteArray {
    val limit = 20 * 1024 * 1024
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val count = input.read(buffer)
        if (count < 0) return output.toByteArray()
        if (output.size().toLong() + count > limit) {
            throw PairingImageException("This image is larger than 20 MB. Crop it around the pairing code and try again.")
        }
        output.write(buffer, 0, count)
    }
}
