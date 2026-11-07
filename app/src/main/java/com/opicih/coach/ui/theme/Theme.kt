package com.opicih.coach.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Notion 스타일(warm minimalism) 토큰. 값의 출처는 getdesign.md/notion 의 preview, preview-dark 이다.
 * 화면 코드는 이 값을 직접 쓰지 않고 MaterialTheme.colorScheme 을 통해서만 참조한다.
 */
object Palette {
    // 공통 포인트 색
    val NotionBlue = Color(0xFF0075DE)
    val NotionBlueDark = Color(0xFF62AEF0)
    val NotionBlueDeep = Color(0xFF005BAB)
    val Green = Color(0xFF1AAE39)
    val Teal = Color(0xFF2A9D99)
    val Orange = Color(0xFFDD5B00)

    // 라이트
    val Canvas = Color(0xFFFFFFFF)
    val CanvasSoft = Color(0xFFF6F5F4)
    val Border = Color(0xFFE6E6E6)
    val TextMuted = Color(0xFF615D59)

    // 다크
    val DarkCanvas = Color(0xFF191817)
    val DarkSoft = Color(0xFF232220)
    val DarkBorder = Color(0x1FFFFFFF)
    val DarkTextMuted = Color(0xFFA39E98)
}

private val Dark = darkColorScheme(
    primary = Palette.NotionBlueDark, onPrimary = Palette.DarkCanvas,
    primaryContainer = Color(0xFF1B2C3D), onPrimaryContainer = Color(0xFFCFE6FA),
    secondary = Palette.Green, onSecondary = Palette.DarkCanvas,
    secondaryContainer = Color(0xFF1B3322), onSecondaryContainer = Color(0xFFCDEFD6),
    tertiary = Palette.Teal, onTertiary = Palette.DarkCanvas,
    tertiaryContainer = Color(0xFF1A3433), onTertiaryContainer = Color(0xFFCDEBE9),
    error = Palette.Orange, onError = Color.White,
    errorContainer = Color(0xFF3D2412), onErrorContainer = Color(0xFFFFDCC4),
    background = Palette.DarkCanvas, onBackground = Color.White,
    surface = Palette.DarkCanvas, onSurface = Color.White,
    surfaceVariant = Palette.DarkSoft, onSurfaceVariant = Palette.DarkTextMuted,
    surfaceContainerLowest = Palette.DarkCanvas, surfaceContainerLow = Palette.DarkSoft,
    surfaceContainer = Palette.DarkSoft, surfaceContainerHigh = Color(0xFF2C2B29), surfaceContainerHighest = Color(0xFF353432),
    outline = Palette.DarkBorder, outlineVariant = Color(0x14FFFFFF),
)

private val Light = lightColorScheme(
    primary = Palette.NotionBlue, onPrimary = Color.White,
    primaryContainer = Color(0xFFE5F1FC), onPrimaryContainer = Palette.NotionBlueDeep,
    secondary = Color(0xFF148A2D), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3F5E7), onSecondaryContainer = Color(0xFF0B4A18),
    tertiary = Color(0xFF1F7C79), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE0F2F1), onTertiaryContainer = Color(0xFF0A3F3D),
    error = Palette.Orange, onError = Color.White,
    errorContainer = Color(0xFFFCE9DC), onErrorContainer = Color(0xFF5A2600),
    background = Palette.Canvas, onBackground = Color.Black,
    surface = Palette.Canvas, onSurface = Color.Black,
    surfaceVariant = Palette.CanvasSoft, onSurfaceVariant = Palette.TextMuted,
    surfaceContainerLowest = Palette.Canvas, surfaceContainerLow = Palette.Canvas,
    surfaceContainer = Palette.CanvasSoft, surfaceContainerHigh = Color(0xFFEFEEEC), surfaceContainerHighest = Color(0xFFE9E8E6),
    outline = Palette.Border, outlineVariant = Color(0xFFEFEEEC),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),   // 입력창
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(16.dp),      // 카드
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

private val base = Typography()
private val AppTypography = Typography(
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
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
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
