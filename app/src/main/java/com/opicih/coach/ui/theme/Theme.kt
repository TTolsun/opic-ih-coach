package com.opicih.coach.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object Palette {
    val Ink = Color(0xFF0E0F12)
    val Surface = Color(0xFF16181D)
    val Surface2 = Color(0xFF1E2128)
    val Line = Color(0xFF2A2E37)
    val Accent = Color(0xFF8FA8FF)
    val Mint = Color(0xFF7FD1B9)
    val Amber = Color(0xFFF2C46D)
    val Coral = Color(0xFFFF8A80)
    val TextHi = Color(0xFFE8ECF4)
    val TextLo = Color(0xFF9AA3B2)
}

private val Dark = darkColorScheme(
    primary = Palette.Accent, onPrimary = Palette.Ink,
    primaryContainer = Color(0xFF26324F), onPrimaryContainer = Color(0xFFDDE4FF),
    secondary = Palette.Mint, onSecondary = Palette.Ink,
    secondaryContainer = Color(0xFF1D3A33), onSecondaryContainer = Color(0xFFCDEFE5),
    tertiary = Palette.Amber, onTertiary = Palette.Ink,
    tertiaryContainer = Color(0xFF3D3220), onTertiaryContainer = Color(0xFFF8E3BC),
    error = Palette.Coral, onError = Palette.Ink,
    errorContainer = Color(0xFF452524), onErrorContainer = Color(0xFFFFDAD6),
    background = Palette.Ink, onBackground = Palette.TextHi,
    surface = Palette.Ink, onSurface = Palette.TextHi,
    surfaceVariant = Palette.Surface2, onSurfaceVariant = Palette.TextLo,
    surfaceContainerLowest = Palette.Ink, surfaceContainerLow = Palette.Surface,
    surfaceContainer = Palette.Surface, surfaceContainerHigh = Palette.Surface2, surfaceContainerHighest = Color(0xFF252933),
    outline = Palette.Line, outlineVariant = Color(0xFF22262E),
)

private val Light = lightColorScheme(
    primary = Color(0xFF3E5BD6), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE4FF), onPrimaryContainer = Color(0xFF0F1D52),
    secondary = Color(0xFF1F7A63), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCDEFE5), onSecondaryContainer = Color(0xFF07382C),
    tertiary = Color(0xFF8A6200), tertiaryContainer = Color(0xFFF8E3BC), onTertiaryContainer = Color(0xFF2B1D00),
    error = Color(0xFFB3261E), errorContainer = Color(0xFFFFDAD6),
    background = Color(0xFFF7F8FB), onBackground = Color(0xFF14161B),
    surface = Color(0xFFF7F8FB), onSurface = Color(0xFF14161B),
    surfaceVariant = Color(0xFFE6E9F0), onSurfaceVariant = Color(0xFF545B68),
    surfaceContainerLow = Color.White, surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEEF1F6),
    outline = Color(0xFFD3D8E1), outlineVariant = Color(0xFFE3E7EE),
)

private val base = Typography()
private val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** English study text gets a slightly larger serif-free reading style. */
val EnglishReading = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 29.sp)

@Composable
fun OpicTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = AppTypography, content = content)
}
