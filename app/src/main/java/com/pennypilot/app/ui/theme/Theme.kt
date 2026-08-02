package com.pennypilot.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = LightSurface,
    primaryContainer = LightPrimarySoft,
    onPrimaryContainer = LightPrimaryInk,
    background = LightBackground,
    onBackground = LightInk,
    surface = LightSurface,
    onSurface = LightInk,
    surfaceVariant = LightSurface2,
    onSurfaceVariant = LightSub,
    outline = LightHairline,
    error = LightIncome,
)

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkBackground,
    primaryContainer = DarkPrimarySoft,
    onPrimaryContainer = DarkPrimaryInk,
    background = DarkBackground,
    onBackground = DarkInk,
    surface = DarkSurface,
    onSurface = DarkInk,
    surfaceVariant = DarkSurface2,
    onSurfaceVariant = DarkSub,
    outline = DarkHairline,
    error = DarkIncome,
)

@Composable
fun PennyPilotTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = PennyPilotTypography,
        shapes = PennyPilotShapes,
        content = content
    )
}
