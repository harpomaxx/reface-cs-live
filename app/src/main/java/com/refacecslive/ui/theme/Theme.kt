package com.refacecslive.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val StageColors = darkColorScheme(
    primary = Color(0xFF53D8A2),
    onPrimary = Color(0xFF062117),
    secondary = Color(0xFFF7B955),
    onSecondary = Color(0xFF2B1900),
    background = Color(0xFF090C12),
    onBackground = Color(0xFFE7ECF3),
    surface = Color(0xFF111722),
    onSurface = Color(0xFFE7ECF3),
    surfaceVariant = Color(0xFF202938),
    onSurfaceVariant = Color(0xFFC3CDDA),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF350000)
)

@Composable
fun RefaceCSLiveTheme(content: @Composable () -> Unit) {
    // The stage UI intentionally stays dark regardless of system mode.
    MaterialTheme(
        colorScheme = StageColors,
        content = content
    )
}
