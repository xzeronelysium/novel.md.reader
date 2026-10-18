package com.mdreader.ui.theme

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

/*
 * Japanese + Swiss: warm paper, sumi ink, one vermilion accent, hairlines
 * instead of shadows, and a strict typographic grid.
 */

private val LightColors = lightColorScheme(
    primary = Color(0xFFC53A2B),
    onPrimary = Color(0xFFF6F4EF),
    primaryContainer = Color(0xFFF0DCD6),
    onPrimaryContainer = Color(0xFF3B0D08),
    secondary = Color(0xFF5F5B54),
    onSecondary = Color(0xFFF6F4EF),
    background = Color(0xFFF6F4EF),
    onBackground = Color(0xFF151515),
    surface = Color(0xFFF6F4EF),
    onSurface = Color(0xFF151515),
    surfaceVariant = Color(0xFFE4E0D7),
    onSurfaceVariant = Color(0xFF5F5B54),
    surfaceContainer = Color(0xFFF1EEE7),
    surfaceContainerHigh = Color(0xFFEDEAE3),
    surfaceContainerLow = Color(0xFFF3F0EA),
    outline = Color(0xFFB8B2A7),
    outlineVariant = Color(0xFFD6D1C7),
    scrim = Color(0xFF0F0E0D),
    error = Color(0xFFB3261E),
    onError = Color(0xFFF6F4EF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE05A44),
    onPrimary = Color(0xFF111110),
    primaryContainer = Color(0xFF3A1712),
    onPrimaryContainer = Color(0xFFF0DCD6),
    secondary = Color(0xFFA29C92),
    onSecondary = Color(0xFF111110),
    background = Color(0xFF111110),
    onBackground = Color(0xFFEEEAE2),
    surface = Color(0xFF111110),
    onSurface = Color(0xFFEEEAE2),
    surfaceVariant = Color(0xFF22201E),
    onSurfaceVariant = Color(0xFFA29C92),
    surfaceContainer = Color(0xFF181715),
    surfaceContainerHigh = Color(0xFF1D1B19),
    surfaceContainerLow = Color(0xFF151412),
    outline = Color(0xFF45413B),
    outlineVariant = Color(0xFF2C2A27),
    scrim = Color(0xFF000000),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 46.sp,
        lineHeight = 42.sp,
        letterSpacing = (-2).sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 23.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.7).sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 17.5.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        fontSize = 17.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 17.sp,
        letterSpacing = 0.1.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 13.sp,
        letterSpacing = 1.6.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 1.55.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 9.sp,
        lineHeight = 12.sp,
        letterSpacing = 1.4.sp,
    ),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

@Composable
fun MDReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
