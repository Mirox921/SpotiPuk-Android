package com.project.puk.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

internal object EmberColors {
    val Canvas = Color(0xFF101116)
    val SurfaceLow = Color(0xFF17181D)
    val Surface = Color(0xFF1B1D23)
    val SurfaceContainer = Color(0xFF22242B)
    val SurfaceContainerHigh = Color(0xFF2C2E36)
    val SurfaceContainerHighest = Color(0xFF373A43)
    val Primary = Color(0xFFFF9654)
    val PrimaryLight = Color(0xFFFFB17D)
    val PrimaryPressed = Color(0xFFE47B3A)
    val PrimaryContainer = Color(0xFF58321F)
    val OnPrimary = Color(0xFF17181D)
    val TextPrimary = Color(0xFFF7F5F2)
    val TextSecondary = Color(0xFFC5C3CC)
    val TextTertiary = Color(0xFFA7AAB5)
    val Outline = Color(0xFF4E515A)
}

private val EmberScheme = darkColorScheme(
    background = EmberColors.Canvas,
    onBackground = EmberColors.TextPrimary,
    surface = EmberColors.Surface,
    onSurface = EmberColors.TextPrimary,
    onSurfaceVariant = EmberColors.TextSecondary,
    surfaceVariant = EmberColors.SurfaceContainerHighest,
    surfaceContainerLowest = EmberColors.Canvas,
    surfaceContainerLow = EmberColors.SurfaceLow,
    surfaceContainer = EmberColors.SurfaceContainer,
    surfaceContainerHigh = EmberColors.SurfaceContainerHigh,
    surfaceContainerHighest = EmberColors.SurfaceContainerHighest,
    primary = EmberColors.Primary,
    onPrimary = EmberColors.OnPrimary,
    primaryContainer = EmberColors.PrimaryContainer,
    onPrimaryContainer = EmberColors.TextPrimary,
    secondary = EmberColors.PrimaryLight,
    onSecondary = EmberColors.OnPrimary,
    secondaryContainer = EmberColors.SurfaceContainerHighest,
    onSecondaryContainer = EmberColors.TextPrimary,
    tertiary = EmberColors.TextTertiary,
    onTertiary = EmberColors.OnPrimary,
    tertiaryContainer = EmberColors.SurfaceContainerHigh,
    onTertiaryContainer = EmberColors.TextPrimary,
    outline = EmberColors.Outline,
    outlineVariant = EmberColors.Outline,
    inverseSurface = EmberColors.TextPrimary,
    inverseOnSurface = EmberColors.SurfaceLow,
    inversePrimary = EmberColors.PrimaryContainer,
    surfaceTint = EmberColors.Primary,
)

@Composable
fun SpotifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = EmberScheme, typography = SairaTypography, content = content)
}
