package dev.herdr.android.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

internal data class PreparedChatAttachment(val file: File, val preview: File?, val name: String, val size: Long, val mime: String?) {
    fun delete() { file.delete(); preview?.delete() }
    override fun toString() = "PreparedChatAttachment(redacted)"
}

/** Only application-owned bounded copies are uploaded; provider files are never modified. */
internal object ChatAttachmentPreparation {
    suspend fun prepare(context: Context, uri: Uri, kind: ChatAttachmentKind): PreparedChatAttachment = withContext(Dispatchers.IO) {
        require(uri.scheme == "content") { "Choose an attachment from the Android file or photo picker." }
        val resolver = context.contentResolver
        var name: String? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) name = cursor.getString(0)
        }
        val selectedName = name ?: if (kind == ChatAttachmentKind.IMAGE) "image" else "attachment"
        ChatAttachmentProtocol.validateName(selectedName)
        val directory = File(context.cacheDir, "chat-attachments").also { check(it.mkdirs() || it.isDirectory) }
        val file = File(directory, UUID.randomUUID().toString())
        val preview = File(directory, "${file.name}.png")
        try {
            val input = resolver.openInputStream(uri) ?: throw IOException("This file could not be opened. Choose it again.")
            var size = 0L
            input.use { source -> file.outputStream().use { destination ->
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    coroutineContext.ensureActive()
                    val count = source.read(buffer)
                    if (count < 0) break
                    size += count
                    require(size <= ChatAttachmentProtocol.MAX_FILE_BYTES) { "Each attachment must be smaller than 16 MiB." }
                    destination.write(buffer, 0, count)
                }
            } }
            require(size > 0) { "This file is empty. Choose another attachment." }
            var mime = resolver.getType(uri)
            var previewFile: File? = null
            if (kind == ChatAttachmentKind.IMAGE) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                mime = bounds.outMimeType
                require(mime in setOf("image/png", "image/jpeg", "image/webp")) {
                    "Choose a PNG, JPEG or WebP image. Export HEIC images as JPEG first."
                }
                require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outWidth.toLong() * bounds.outHeight <= 16_000_000) {
                    "Choose an image with no more than 16 million pixels."
                }
                var sample = 1
                while (maxOf(bounds.outWidth, bounds.outHeight) / sample > 768) sample *= 2
                val bitmap = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
                    ?: throw IOException("This image could not be decoded. Choose another image.")
                try { preview.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
                finally { bitmap.recycle() }
                previewFile = preview
            }
            PreparedChatAttachment(file, previewFile, selectedName, size, mime)
        } catch (error: Throwable) {
            file.delete(); preview.delete()
            throw error
        }
    }
}
