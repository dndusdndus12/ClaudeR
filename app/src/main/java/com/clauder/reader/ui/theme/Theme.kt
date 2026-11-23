package com.clauder.reader.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** UI 참고 이미지(ui_ref)의 색감: 흰 배경, 파란 강조색, 옅은 회색 카드/테두리. */
object AppColors {
    val Blue = Color(0xFF1A6FE8)
    val Background = Color(0xFFFFFFFF)
    val SurfaceGray = Color(0xFFF4F6F9)
    val Outline = Color(0xFFD9DDE3)
    val TextPrimary = Color(0xFF16181D)
    val TextSecondary = Color(0xFF7A808A)
    val Green = Color(0xFF1DB954)
}

private val LightColors = lightColorScheme(
    primary = AppColors.Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE8F0FE),
    onPrimaryContainer = AppColors.Blue,
    background = AppColors.Background,
    onBackground = AppColors.TextPrimary,
    surface = AppColors.Background,
    onSurface = AppColors.TextPrimary,
    surfaceVariant = AppColors.SurfaceGray,
    onSurfaceVariant = AppColors.TextSecondary,
    outline = AppColors.Outline,
    outlineVariant = AppColors.Outline,
)

@Composable
fun ClauderTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = LightColors, content = content)
}
