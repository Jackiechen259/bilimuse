package com.bilimuse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val BiliBg = Color(0xFF090B10)
val BiliPanel = Color(0xFF11141C)
val BiliPanel2 = Color(0xFF171B26)
val BiliLine = Color(0xFF222834)
val BiliText = Color(0xFFE9EDF5)
val BiliMuted = Color(0xFF8A93A6)
val BiliPink = Color(0xFFFB7299)
val BiliBlue = Color(0xFF00AEEC)
val BiliPurple = Color(0xFFA55EEA)
val BiliDanger = Color(0xFFFF6B6B)

val AppBackgroundBrush = Brush.verticalGradient(
    colorStops = arrayOf(
        0.0f to Color(0xFF1A0F24),
        0.35f to BiliBg,
        1.0f to Color(0xFF071018),
    ),
)

val CoverFallbackBrush = Brush.linearGradient(
    listOf(
        BiliPink.copy(alpha = 0.75f),
        BiliPurple.copy(alpha = 0.65f),
        BiliBlue.copy(alpha = 0.55f),
    ),
)

private val Colors = darkColorScheme(
    primary = BiliPink,
    onPrimary = Color(0xFF2A0612),
    primaryContainer = Color(0xFF3A1624),
    onPrimaryContainer = Color(0xFFFFD9E4),
    secondary = BiliBlue,
    onSecondary = Color(0xFF001E28),
    background = BiliBg,
    onBackground = BiliText,
    surface = BiliPanel,
    onSurface = BiliText,
    surfaceVariant = BiliPanel2,
    onSurfaceVariant = BiliMuted,
    outline = BiliLine,
    outlineVariant = BiliLine,
    error = BiliDanger,
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.2.sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, letterSpacing = 0.4.sp),
)

@Composable
fun BiliMuseTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        typography = AppTypography,
    ) {
        // 根容器是自绘渐变 Box 而不是 Surface：显式给所有 Text 一个默认前景色，
        // 否则 Material3 的 LocalContentColor 默认是黑色，深色界面上看不清。
        CompositionLocalProvider(LocalContentColor provides BiliText, content = content)
    }
}

@Composable
fun AppBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(AppBackgroundBrush)) {
        content()
    }
}
