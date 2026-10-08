package dev.herdr.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

internal val Mint = Color(0xFFB4F0C0)
internal val Amber = Color(0xFFF0CB83)
internal val CanvasColor = Color(0xFF0D1117)
internal val TerminalColor = Color(0xFF080C11)

private val Colors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF123A24),
    primaryContainer = Color(0xFF214532),
    onPrimaryContainer = Color(0xFFD0F6D5),
    secondary = Color(0xFFA9BFD7),
    secondaryContainer = Color(0xFF283746),
    onSecondaryContainer = Color(0xFFD7E8FC),
    tertiary = Amber,
    background = CanvasColor,
    onBackground = Color(0xFFE7EDF5),
    surface = Color(0xFF121820),
    onSurface = Color(0xFFE7EDF5),
    surfaceVariant = Color(0xFF202A35),
    onSurfaceVariant = Color(0xFF9AA9B9),
    outline = Color(0xFF475261),
    outlineVariant = Color(0xFF293340),
    error = Color(0xFFFFB4AB),
    errorContainer = Color(0xFF47211F),
    onErrorContainer = Color(0xFFFFDAD5),
)

@Composable
fun HerdrTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = Typography(),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp),
        ),
        content = content,
    )
}
