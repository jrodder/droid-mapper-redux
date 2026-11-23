package com.jrod.droidmapper.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Parchment, brown ink and burnt orange: the palette of the research map pages.
private val Light = lightColorScheme(
    primary = Color(0xFFC2410C), onPrimary = Color(0xFFFFFDF8),
    primaryContainer = Color(0xFFF3D9A0), onPrimaryContainer = Color(0xFF3A2A0A),
    secondary = Color(0xFF7A5A3A), onSecondary = Color(0xFFFFFDF8),
    secondaryContainer = Color(0xFFE6DCC6), onSecondaryContainer = Color(0xFF22201C),
    tertiary = Color(0xFF1D7A4F), onTertiary = Color(0xFFFFFDF8),
    background = Color(0xFFF4F1EA), onBackground = Color(0xFF22201C),
    surface = Color(0xFFFFFDF8), onSurface = Color(0xFF22201C),
    surfaceVariant = Color(0xFFEBE6DA), onSurfaceVariant = Color(0xFF6D675C),
    surfaceContainerLowest = Color(0xFFFFFDF8), surfaceContainerLow = Color(0xFFF8F5EE),
    surfaceContainer = Color(0xFFF4F1EA), surfaceContainerHigh = Color(0xFFF1ECE0),
    surfaceContainerHighest = Color(0xFFEBE6DA),
    outline = Color(0xFF978F7C), outlineVariant = Color(0xFFDDD6C4),
    error = Color(0xFF9B1C1C), onError = Color(0xFFFFFDF8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFB923C), onPrimary = Color(0xFF2A1503),
    primaryContainer = Color(0xFF6B5220), onPrimaryContainer = Color(0xFFF6E7C4),
    secondary = Color(0xFFCDB592), onSecondary = Color(0xFF2A2117),
    secondaryContainer = Color(0xFF3A342A), onSecondaryContainer = Color(0xFFECE7DC),
    tertiary = Color(0xFF5FD39B), onTertiary = Color(0xFF06281A),
    background = Color(0xFF1B1A17), onBackground = Color(0xFFECE7DC),
    surface = Color(0xFF3A372F), onSurface = Color(0xFFECE7DC),
    surfaceVariant = Color(0xFF25231F), onSurfaceVariant = Color(0xFFA39C8E),
    surfaceContainerLowest = Color(0xFF161512), surfaceContainerLow = Color(0xFF1F1E1A),
    surfaceContainer = Color(0xFF25231F), surfaceContainerHigh = Color(0xFF2C2A25),
    surfaceContainerHighest = Color(0xFF34312B),
    outline = Color(0xFF6F6959), outlineVariant = Color(0xFF2C2A25),
    error = Color(0xFFF2A0A0), onError = Color(0xFF3A0B0B),
)

@Composable
fun MapperTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
