package com.bilimuse.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 三套主题与 Web 的 ink / paper / cream 完全同名、同值。 */
enum class AppTheme(val key: String, val label: String) {
    INK("ink", "深色 · 墨"),
    PAPER("paper", "浅色 · 纸"),
    CREAM("cream", "暖调 · 米");

    val isLight: Boolean get() = this != INK

    companion object {
        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: INK
    }
}

@Immutable
data class BiliPalette(
    val bg: Color,
    val panel: Color,
    val panel2: Color,
    val line: Color,
    val lineSoft: Color,
    val lineStrong: Color,
    val text: Color,
    val text2: Color,
    val muted: Color,
    val faint: Color,
    val pink: Color,
    val pinkStrong: Color,
    val pinkText: Color,
    val onPink: Color,
    val danger: Color,
    val track: Color,
    val selected: Color,
    val isLight: Boolean,
)

private val InkPalette = BiliPalette(
    bg = Color(0xFF111114),
    panel = Color(0xFF17171A),
    panel2 = Color(0xFF1D1D21),
    line = Color(0xFF2A2A2F),
    lineSoft = Color(0xFF242429),
    lineStrong = Color(0xFF6B6B76),
    text = Color(0xFFF4F3F1),
    text2 = Color(0xFFB9B8C0),
    muted = Color(0xFF86858F),
    faint = Color(0xFF6B6B76),
    pink = Color(0xFFFB7299),
    pinkStrong = Color(0xFFFB7299),
    pinkText = Color(0xFFFB7299),
    onPink = Color(0xFF1A0A10),
    danger = Color(0xFFFF6B6B),
    track = Color(0xFF3A3A40),
    selected = Color(0xFF1D1D21),
    isLight = false,
)

private val PaperPalette = BiliPalette(
    bg = Color(0xFFFFFFFF),
    panel = Color(0xFFFFFFFF),
    panel2 = Color(0xFFF6F6F3),
    line = Color(0xFFE8E8EC),
    lineSoft = Color(0xFFF0F0F3),
    lineStrong = Color(0xFF8A8A92),
    text = Color(0xFF17171A),
    text2 = Color(0xFF44444C),
    muted = Color(0xFF6B6B76),
    faint = Color(0xFF8A8A92),
    pink = Color(0xFFFB7299),
    pinkStrong = Color(0xFFD63669),
    pinkText = Color(0xFFB4235A),
    onPink = Color(0xFF1A0A10),
    danger = Color(0xFFC62828),
    track = Color(0xFFE8E8EC),
    selected = Color(0xFFF6F6F3),
    isLight = true,
)

private val CreamPalette = BiliPalette(
    bg = Color(0xFFF2EBDF),
    panel = Color(0xFFFAF5EC),
    panel2 = Color(0xFFFFFBF4),
    line = Color(0xFFDFD4C1),
    lineSoft = Color(0xFFE8DFCE),
    lineStrong = Color(0xFF8A7F6D),
    text = Color(0xFF1B1A17),
    text2 = Color(0xFF514A3F),
    muted = Color(0xFF6A6152),
    faint = Color(0xFF8A7F6D),
    pink = Color(0xFFFB7299),
    pinkStrong = Color(0xFFD63669),
    pinkText = Color(0xFFB4235A),
    onPink = Color(0xFF1A0A10),
    danger = Color(0xFFB3261E),
    track = Color(0xFFDFD4C1),
    selected = Color(0xFFFFFBF4),
    isLight = true,
)

val LocalBiliPalette = staticCompositionLocalOf { InkPalette }

private fun paletteFor(theme: AppTheme): BiliPalette = when (theme) {
    AppTheme.INK -> InkPalette
    AppTheme.PAPER -> PaperPalette
    AppTheme.CREAM -> CreamPalette
}

/* 主题色统一通过 CompositionLocal 读取；在非 composable 的 draw lambda 里
   需要先提升成局部 val，避免在绘制阶段读取组合局部。 */
val BiliBg: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.bg
val BiliPanel: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.panel
val BiliPanel2: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.panel2
val BiliLine: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.line
val BiliLineSoft: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.lineSoft
val BiliLineStrong: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.lineStrong
val BiliText: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.text
val BiliText2: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.text2
val BiliMuted: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.muted
val BiliFaint: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.faint
val BiliPink: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.pink
val BiliPinkStrong: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.pinkStrong
val BiliPinkText: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.pinkText
val BiliOnPink: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.onPink
val BiliDanger: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.danger
val BiliTrack: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.track
val BiliSelected: Color
    @Composable @ReadOnlyComposable get() = LocalBiliPalette.current.selected

private fun schemeFor(palette: BiliPalette) = if (palette.isLight) {
    lightColorScheme(
        primary = palette.pink,
        onPrimary = palette.onPink,
        primaryContainer = palette.selected,
        onPrimaryContainer = palette.pinkText,
        secondary = palette.pinkText,
        onSecondary = palette.bg,
        background = palette.bg,
        onBackground = palette.text,
        surface = palette.panel,
        onSurface = palette.text,
        surfaceVariant = palette.panel2,
        onSurfaceVariant = palette.muted,
        outline = palette.line,
        outlineVariant = palette.lineSoft,
        error = palette.danger,
        surfaceTint = Color.Transparent,
    )
} else {
    darkColorScheme(
        primary = palette.pink,
        onPrimary = palette.onPink,
        primaryContainer = palette.selected,
        onPrimaryContainer = palette.pinkText,
        secondary = palette.pink,
        onSecondary = palette.onPink,
        background = palette.bg,
        onBackground = palette.text,
        surface = palette.panel,
        onSurface = palette.text,
        surfaceVariant = palette.panel2,
        onSurfaceVariant = palette.muted,
        outline = palette.line,
        outlineVariant = palette.lineSoft,
        error = palette.danger,
        surfaceTint = Color.Transparent,
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(2.dp),
    small = RoundedCornerShape(2.dp),
    medium = RoundedCornerShape(2.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp),
)

@Composable
fun BiliMuseTheme(
    theme: AppTheme = AppTheme.INK,
    content: @Composable () -> Unit,
) {
    val palette = paletteFor(theme)
    MaterialTheme(
        colorScheme = schemeFor(palette),
        typography = AppTypography,
        shapes = AppShapes,
    ) {
        // 根容器是自绘 Box：显式给所有 Text 一个默认前景色，
        // 否则 Material3 的 LocalContentColor 默认是黑色，深色界面上看不清。
        CompositionLocalProvider(
            LocalBiliPalette provides palette,
            LocalContentColor provides palette.text,
            content = content,
        )
    }
}

@Composable
fun AppBackground(content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().background(BiliBg)) {
        content()
    }
}
