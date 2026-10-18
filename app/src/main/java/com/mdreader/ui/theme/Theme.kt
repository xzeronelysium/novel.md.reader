package com.mdreader.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF2F5D8A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD5E4FF),
    onPrimaryContainer = Color(0xFF0A1D33),
    secondary = Color(0xFF52606F),
    onSecondary = Color.White,
    background = Color(0xFFFDFBFF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFFDFBFF),
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE2E2E9),
    onSurfaceVariant = Color(0xFF44474E),
    outlineVariant = Color(0xFFC4C6CF),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9FC6FF),
    onPrimary = Color(0xFF00325B),
    primaryContainer = Color(0xFF12456F),
    onPrimaryContainer = Color(0xFFD5E4FF),
    secondary = Color(0xFFBAC8D9),
    onSecondary = Color(0xFF243141),
    background = Color(0xFF121316),
    onBackground = Color(0xFFE3E2E6),
    surface = Color(0xFF121316),
    onSurface = Color(0xFFE3E2E6),
    surfaceVariant = Color(0xFF44474E),
    onSurfaceVariant = Color(0xFFC4C6CF),
    outlineVariant = Color(0xFF44474E),
)

@Composable
fun MDReaderTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
