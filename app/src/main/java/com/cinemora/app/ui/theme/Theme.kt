package com.cinemora.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CinemoraColors = darkColorScheme(
    primary = Purple,
    onPrimary = TextPrimary,
    secondary = PurpleLight,
    onSecondary = Night,
    background = Night,
    onBackground = TextPrimary,
    surface = SurfaceNight,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceElevated,
    onSurfaceVariant = TextMuted,
    outline = DividerColor
)

@Composable
fun CinemoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CinemoraColors,
        content = content
    )
}
