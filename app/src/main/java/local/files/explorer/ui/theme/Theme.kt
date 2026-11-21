package local.files.explorer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import local.files.explorer.settings.ThemeMode

val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF3550D1), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE3FF), onPrimaryContainer = Color(0xFF0B1A63),
    secondary = Color(0xFF575E79), onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFE2FA), onSecondaryContainer = Color(0xFF141B33),
    tertiary = Color(0xFF00807F), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFB2EFEC), onTertiaryContainer = Color(0xFF002020),
    error = Color(0xFFBA1A1A), errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002),
    background = Color(0xFFFAF9FF), onBackground = Color(0xFF1A1B22),
    surface = Color(0xFFFAF9FF), onSurface = Color(0xFF1A1B22),
    surfaceVariant = Color(0xFFE2E2EE), onSurfaceVariant = Color(0xFF45464F),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF4F3FB),
    surfaceContainer = Color(0xFFEEEDF6), surfaceContainerHigh = Color(0xFFE8E7F0),
    surfaceContainerHighest = Color(0xFFE2E2EA),
    outline = Color(0xFF767680), outlineVariant = Color(0xFFC6C6D0),
    inverseSurface = Color(0xFF2F3037), inverseOnSurface = Color(0xFFF1F0F8), inversePrimary = Color(0xFFB8C4FF),
)

val DarkColors: ColorScheme = darkColorScheme(
    primary = Color(0xFFB8C4FF), onPrimary = Color(0xFF13288F),
    primaryContainer = Color(0xFF2E44B8), onPrimaryContainer = Color(0xFFDDE3FF),
    secondary = Color(0xFFC3C6E2), onSecondary = Color(0xFF2C3048),
    secondaryContainer = Color(0xFF3F4560), onSecondaryContainer = Color(0xFFDFE2FA),
    tertiary = Color(0xFF7FD6D3), onTertiary = Color(0xFF003736),
    tertiaryContainer = Color(0xFF00504F), onTertiaryContainer = Color(0xFFB2EFEC),
    error = Color(0xFFFFB4AB), errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF121318), onBackground = Color(0xFFE3E1EA),
    surface = Color(0xFF121318), onSurface = Color(0xFFE3E1EA),
    surfaceVariant = Color(0xFF45464F), onSurfaceVariant = Color(0xFFC6C6D0),
    surfaceContainerLowest = Color(0xFF0D0E13), surfaceContainerLow = Color(0xFF1A1B21),
    surfaceContainer = Color(0xFF1E1F25), surfaceContainerHigh = Color(0xFF292A30),
    surfaceContainerHighest = Color(0xFF34343B),
    outline = Color(0xFF90909A), outlineVariant = Color(0xFF45464F),
    inverseSurface = Color(0xFFE3E1EA), inverseOnSurface = Color(0xFF2F3037), inversePrimary = Color(0xFF3550D1),
)

private val Base = Typography()
val FileManagerTypography = Typography(
    headlineMedium = Base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = Base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge,
    bodyMedium = Base.bodyMedium,
    labelLarge = Base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    labelMedium = Base.labelMedium.copy(fontWeight = FontWeight.Medium),
)

val FileManagerShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Per-file-type accent colours, tuned for both themes. */
data class TypePalette(val dark: Boolean)
val LocalTypePalette = staticCompositionLocalOf { TypePalette(false) }

val MonoStyle = TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 12.sp)

fun ThemeMode.isDark(system: Boolean): Boolean = when (this) {
    ThemeMode.SYSTEM -> system
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun FileManagerTheme(mode: ThemeMode = ThemeMode.SYSTEM, darkOverride: Boolean? = null, content: @Composable () -> Unit) {
    val dark = darkOverride ?: mode.isDark(isSystemInDarkTheme())
    CompositionLocalProvider(LocalTypePalette provides TypePalette(dark)) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = FileManagerTypography,
            shapes = FileManagerShapes,
            content = content,
        )
    }
}
