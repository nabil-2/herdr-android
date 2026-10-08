package dev.herdr.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

internal enum class Symbol { BACK, PLUS, REFRESH, SERVER, NEXT, CLOSE, TERMINAL, SEND, DELETE, QR }

/** Small local vector set; no image assets or icon-font dependency. */
@Composable
internal fun SymbolIcon(symbol: Symbol, description: String? = null, modifier: Modifier = Modifier) {
    val color = LocalContentColor.current
    Canvas(modifier.size(24.dp).semantics { if (description != null) contentDescription = description }) {
        val unit = size.width / 24f
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) =
            drawLine(color, Offset(x1 * unit, y1 * unit), Offset(x2 * unit, y2 * unit), 1.8f * unit)
        when (symbol) {
            Symbol.QR -> {
                for ((x, y) in listOf(3f to 3f, 14f to 3f, 3f to 14f)) {
                    drawRoundRect(color, Offset(x * unit, y * unit), Size(7f * unit, 7f * unit), CornerRadius(unit), style = Stroke(1.8f * unit))
                    drawRect(color, Offset((x + 2.5f) * unit, (y + 2.5f) * unit), Size(2f * unit, 2f * unit))
                }
                line(14f, 15f, 20f, 15f); line(15f, 15f, 15f, 20f)
                drawRect(color, Offset(18f * unit, 18f * unit), Size(3f * unit, 3f * unit))
            }
            Symbol.BACK -> { line(19f, 12f, 5f, 12f); line(5f, 12f, 11f, 6f); line(5f, 12f, 11f, 18f) }
            Symbol.PLUS -> { line(5f, 12f, 19f, 12f); line(12f, 5f, 12f, 19f) }
            Symbol.CLOSE -> { line(6f, 6f, 18f, 18f); line(18f, 6f, 6f, 18f) }
            Symbol.NEXT -> { line(9f, 6f, 15f, 12f); line(15f, 12f, 9f, 18f) }
            Symbol.SEND -> {
                val path = Path().apply { moveTo(3f * unit, 3f * unit); lineTo(21f * unit, 12f * unit); lineTo(3f * unit, 21f * unit); lineTo(7f * unit, 12f * unit); close() }
                drawPath(path, color, style = Stroke(1.8f * unit)); line(7f, 12f, 21f, 12f)
            }
            Symbol.REFRESH -> {
                drawArc(color, 40f, 285f, false, Offset(4f * unit, 4f * unit), Size(16f * unit, 16f * unit), style = Stroke(1.8f * unit))
                line(19f, 4f, 19f, 10f); line(19f, 10f, 13f, 10f)
            }
            Symbol.SERVER -> {
                for (y in listOf(4f, 13f)) {
                    drawRoundRect(color, Offset(3f * unit, y * unit), Size(18f * unit, 7f * unit), CornerRadius(1.5f * unit), style = Stroke(1.8f * unit))
                    drawCircle(color, 1f * unit, Offset(7f * unit, (y + 3.5f) * unit)); line(12f, y + 3.5f, 17f, y + 3.5f)
                }
            }
            Symbol.TERMINAL -> {
                drawRoundRect(color, Offset(2f * unit, 4f * unit), Size(20f * unit, 16f * unit), CornerRadius(2f * unit), style = Stroke(1.8f * unit))
                line(6f, 8f, 10f, 12f); line(10f, 12f, 6f, 16f); line(13f, 16f, 18f, 16f)
            }
            Symbol.DELETE -> {
                line(4f, 6f, 20f, 6f); line(9f, 3f, 15f, 3f)
                line(6f, 6f, 7f, 21f); line(7f, 21f, 17f, 21f); line(17f, 21f, 18f, 6f)
                line(10f, 10f, 10f, 17f); line(14f, 10f, 14f, 17f)
            }
        }
    }
}
