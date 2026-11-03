package io.github.manfug.trainpit.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF6E3AB5), onPrimary = Color.White,
    primaryContainer = Color(0xFFEEDCFF), onPrimaryContainer = Color(0xFF280056),
    secondary = Color(0xFFB3263E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFD9DF), onSecondaryContainer = Color(0xFF400012),
    tertiary = Color(0xFF944B00), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDCC2), onTertiaryContainer = Color(0xFF301400),
    background = Color(0xFFFFF7FD), onBackground = Color(0xFF201A24),
    surface = Color(0xFFFFF7FD), onSurface = Color(0xFF201A24),
    surfaceVariant = Color(0xFFEADFED), onSurfaceVariant = Color(0xFF4C4354),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF9F0FC),
    surfaceContainer = Color(0xFFF3EAF6), surfaceContainerHigh = Color(0xFFEDE4F0),
    surfaceContainerHighest = Color(0xFFE7DEEA),
    outline = Color(0xFF7D7385), outlineVariant = Color(0xFFCEC3D3),
    inverseSurface = Color(0xFF352E3A), inverseOnSurface = Color(0xFFF7EDFA),
    inversePrimary = Color(0xFFD9B9FF), surfaceTint = Color(0xFF6E3AB5),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFFD9B9FF), onPrimary = Color(0xFF3F087D),
    primaryContainer = Color(0xFF55219A), onPrimaryContainer = Color(0xFFEEDCFF),
    secondary = Color(0xFFFFB1C0), onSecondary = Color(0xFF680026),
    secondaryContainer = Color(0xFF8D0A30), onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFFFFB77D), onTertiary = Color(0xFF502400),
    tertiaryContainer = Color(0xFF713800), onTertiaryContainer = Color(0xFFFFDCC2),
    background = Color(0xFF17121C), onBackground = Color(0xFFEAE0ED),
    surface = Color(0xFF17121C), onSurface = Color(0xFFEAE0ED),
    surfaceVariant = Color(0xFF4C4354), onSurfaceVariant = Color(0xFFCEC3D3),
    surfaceContainerLowest = Color(0xFF110C16), surfaceContainerLow = Color(0xFF201A24),
    surfaceContainer = Color(0xFF241E29), surfaceContainerHigh = Color(0xFF2F2834),
    surfaceContainerHighest = Color(0xFF3A333F),
    outline = Color(0xFF978C9F), outlineVariant = Color(0xFF4C4354),
    inverseSurface = Color(0xFFEAE0ED), inverseOnSurface = Color(0xFF352E3A),
    inversePrimary = Color(0xFF6E3AB5), surfaceTint = Color(0xFFD9B9FF),
)

@Composable
internal fun TrainPitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
