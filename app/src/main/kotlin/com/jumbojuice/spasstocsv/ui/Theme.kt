package com.jumbojuice.spasstocsv.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6C55),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2D6),
    onPrimaryContainer = Color(0xFF002018),
    secondary = Color(0xFF4B635B),
    surfaceVariant = Color(0xFFDBE5DF),
    error = Color(0xFFBA1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8AD6BA),
    onPrimary = Color(0xFF003828),
    primaryContainer = Color(0xFF00513B),
    onPrimaryContainer = Color(0xFFA6F2D6),
    secondary = Color(0xFFB2CCC1),
    surfaceVariant = Color(0xFF3F4945),
    error = Color(0xFFFFB4AB),
)

/** A small, self-contained theme. No dynamic colour, so it looks the same everywhere. */
@Composable
fun SpassToCsvTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
