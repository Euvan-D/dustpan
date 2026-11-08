package app.dustpan.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Mint = Color(0xFF2EE6A6)
val Gold = Color(0xFFFFD166)

private val DustpanColors = darkColorScheme(
    primary = Mint,
    onPrimary = Color(0xFF00281A),
    primaryContainer = Color(0xFF0F3A2C),
    onPrimaryContainer = Color(0xFFB9F6DE),
    secondary = Gold,
    onSecondary = Color(0xFF3A2A00),
    secondaryContainer = Color(0xFF173A2F),
    onSecondaryContainer = Color(0xFFB9F6DE),
    background = Color(0xFF0B0D14),
    onBackground = Color(0xFFE7E9F0),
    surface = Color(0xFF0B0D14),
    onSurface = Color(0xFFE7E9F0),
    surfaceVariant = Color(0xFF1B1F2C),
    onSurfaceVariant = Color(0xFFA7ADBF),
    surfaceContainerLowest = Color(0xFF07090E),
    surfaceContainerLow = Color(0xFF10131C),
    surfaceContainer = Color(0xFF141824),
    surfaceContainerHigh = Color(0xFF1A1E2B),
    surfaceContainerHighest = Color(0xFF212636),
    outline = Color(0xFF3B4256),
    outlineVariant = Color(0xFF2A3040),
    error = Color(0xFFFF7A7A),
)

@Composable
fun DustpanTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = DustpanColors, content = content)
}
