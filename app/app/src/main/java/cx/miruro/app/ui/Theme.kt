package cx.miruro.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Accent = Color(0xFF7C5CFF)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2B2058),
    onPrimaryContainer = Color(0xFFDDD3FF),
    secondary = Color(0xFF9B8CFF),
    onSecondary = Color(0xFF141024),
    background = Color(0xFF0B0B0D),
    onBackground = Color(0xFFECECF1),
    surface = Color(0xFF111114),
    onSurface = Color(0xFFECECF1),
    surfaceVariant = Color(0xFF1D1D22),
    onSurfaceVariant = Color(0xFFB6B6C2),
    surfaceContainer = Color(0xFF161619),
    outline = Color(0xFF35353D),
    outlineVariant = Color(0xFF26262C),
    error = Color(0xFFFF6B6B)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF5B3EE8),
    onPrimary = Color.White,
    secondary = Color(0xFF6F5BD8),
    background = Color(0xFFFAFAFC),
    surface = Color(0xFFFFFFFF)
)

@Composable
fun MiruroTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
