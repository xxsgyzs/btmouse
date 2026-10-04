package com.btmouse.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    background = Color(0xFFFAFAFA),
    surface = Color.White
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF90CAF9),
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E)
)

@Composable
fun BtMouseTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = Typography(),
        content = content
    )
}