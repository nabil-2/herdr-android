package dev.herdr.android.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PairingImageDeviceTest {
    private val payload = """{"type":"herdr-pocket-pair","version":1,"url":"https://dust.example.ts.net","name":"Dust","code":"${"A".repeat(43)}","expiresAt":9223372036854775807}"""

    @Test
    fun localPngScreenshotWithQrAmongInterfaceContentDecodesAfterDownsampling() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "pairing-image-test-${UUID.randomUUID()}.png")
        val screenshot = Bitmap.createBitmap(2160, 3840, Bitmap.Config.ARGB_8888)
        val qrSize = 960
        val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, qrSize, qrSize)
        val qr = Bitmap.createBitmap(IntArray(qrSize * qrSize) { index ->
            if (matrix[index % qrSize, index / qrSize]) Color.rgb(20, 38, 57) else Color.WHITE
        }, qrSize, qrSize, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(screenshot)
            canvas.drawColor(Color.rgb(244, 246, 250))
            canvas.drawRect(0f, 0f, 2160f, 480f, Paint().apply { color = Color.rgb(25, 37, 50) })
            canvas.drawRect(0f, 3100f, 2160f, 3840f, Paint().apply { color = Color.rgb(186, 219, 255) })
            canvas.drawBitmap(qr, 600f, 1380f, null)
            file.outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            assertEquals(PairingImageResult.Code(payload), readPairingImage(context.contentResolver, Uri.fromFile(file)))
        } finally {
            screenshot.recycle()
            qr.recycle()
            file.delete()
        }
    }

    @Test
    fun malformedImageAndImageWithoutQrReturnHelpfulErrors() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "pairing-image-test-${UUID.randomUUID()}.png")
        val blank = Bitmap.createBitmap(320, 480, Bitmap.Config.ARGB_8888)
        try {
            file.writeText("This is not image data.")
            assertTrue(readPairingImage(context.contentResolver, Uri.fromFile(file)) is PairingImageResult.Error)
            blank.eraseColor(Color.WHITE)
            file.outputStream().use { assertTrue(blank.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            val result = readPairingImage(context.contentResolver, Uri.fromFile(file))
            assertTrue(result is PairingImageResult.Error && result.message.startsWith("No QR code was found."))
        } finally {
            blank.recycle()
            file.delete()
        }
    }
}
