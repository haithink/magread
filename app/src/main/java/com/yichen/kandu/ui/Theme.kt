package com.yichen.kandu.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Green = Color(0xFF1B4B43)
private val GreenDark = Color(0xFF9CD3C7)
private val Lime = Color(0xFF7FA829)

private val LightColors = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    secondary = Lime,
    onSecondary = Color.White,
    primaryContainer = Color(0xFFCDE8E1),
    onPrimaryContainer = Color(0xFF06231E),
    secondaryContainer = Color(0xFFE4EDC7),
    onSecondaryContainer = Color(0xFF232A0A),
    surface = Color(0xFFF7FAF7),
    background = Color(0xFFF7FAF7)
)

private val DarkColors = darkColorScheme(
    primary = GreenDark,
    onPrimary = Color(0xFF00382F),
    secondary = Color(0xFFC4E88B),
    onSecondary = Color(0xFF243603),
    primaryContainer = Color(0xFF26524A),
    onPrimaryContainer = Color(0xFFCDE8E1)
)

@Composable
fun KanduTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content
    )
}
