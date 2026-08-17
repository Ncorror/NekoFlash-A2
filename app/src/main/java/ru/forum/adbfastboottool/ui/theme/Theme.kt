package ru.forum.adbfastboottool.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = NekoBlue,
    primaryContainer = NekoBlueContainer,
    onPrimaryContainer = NekoOnBlueContainer,
    surface = Color(0xFFF9F9FF),
    surfaceContainer = Color(0xFFF0F0F7),
)

private val DarkColors = darkColorScheme(
    primary = NekoDarkBlue,
    primaryContainer = NekoDarkBlueContainer,
    onPrimaryContainer = NekoDarkOnBlueContainer,
    surface = Color(0xFF111318),
    surfaceContainer = Color(0xFF1D2026),
)

@Composable
fun NekoFlashTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = NekoFlashTypography,
        shapes = NekoFlashShapes,
        content = content,
    )
}
