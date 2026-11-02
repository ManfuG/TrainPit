package io.github.manfug.trainpit.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5C), onPrimary = Color.White,
    primaryContainer = Color(0xFF9CF2DC), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A635D), background = Color(0xFFF6FAF7),
    surface = Color(0xFFF6FAF7), surfaceVariant = Color(0xFFDCE5DF),
    secondaryContainer = Color(0xFFCFE8DE), onSecondaryContainer = Color(0xFF102F27),
    surfaceContainer = Color(0xFFECF2ED),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5C0), onPrimary = Color(0xFF00382E),
    primaryContainer = Color(0xFF005144), onPrimaryContainer = Color(0xFF9CF2DC),
    secondary = Color(0xFFB1CCC2), background = Color(0xFF101512),
    surface = Color(0xFF101512), surfaceVariant = Color(0xFF3F4943),
    secondaryContainer = Color(0xFF304C42), onSecondaryContainer = Color(0xFFD5E9DF),
    surfaceContainer = Color(0xFF1B241F),
)

@Composable
internal fun TrainPitTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}
