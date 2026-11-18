package com.mdreader.desktop.theme

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

/** Warm paper, sumi ink, and the shared vermilion accent used by the Android reader. */
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
    outline = Color(0xFFB8B2A7),
    outlineVariant = Color(0xFFD6D1C7),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE05A44),
    onPrimary = Color(0xFF111110),
    secondary = Color(0xFFA29C92),
    onSecondary = Color(0xFF111110),
    primaryContainer = Color(0xFF3A1712),
    onPrimaryContainer = Color(0xFFF0DCD6),
    background = Color(0xFF111110),
    onBackground = Color(0xFFEEEAE2),
    surface = Color(0xFF111110),
    onSurface = Color(0xFFEEEAE2),
    surfaceVariant = Color(0xFF22201E),
    onSurfaceVariant = Color(0xFFA29C92),
    surfaceContainer = Color(0xFF181715),
    surfaceContainerHigh = Color(0xFF1D1B19),
    outline = Color(0xFF45413B),
    outlineVariant = Color(0xFF2C2A27),
)

private val AppTypography = Typography(
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
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontSize = 17.5.sp,
        lineHeight = 32.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 1.4.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 11.sp,
        letterSpacing = 1.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 14.sp,
        lineHeight = 22.sp,
        color = Color(0xFF4F5654),
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
fun DesktopReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
