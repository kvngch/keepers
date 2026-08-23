package fr.kvngch.keepers.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F6B50),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA9F2D0),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4C6358),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCEE9DB),
    onSecondaryContainer = Color(0xFF082017),
    background = Color(0xFFF6F9F5),
    onBackground = Color(0xFF171D19),
    surface = Color(0xFFF6F9F5),
    onSurface = Color(0xFF171D19),
    surfaceVariant = Color(0xFFDCE5DD),
    onSurfaceVariant = Color(0xFF404943),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEBEFEA),
    outline = Color(0xFF707973),
    outlineVariant = Color(0xFFC0C9C1),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8ED6B2),
    onPrimary = Color(0xFF003825),
    primaryContainer = Color(0xFF005138),
    onPrimaryContainer = Color(0xFFA9F2D0),
    secondary = Color(0xFFB3CCBE),
    onSecondary = Color(0xFF1F352A),
    secondaryContainer = Color(0xFF354B40),
    onSecondaryContainer = Color(0xFFCEE9DB),
    background = Color(0xFF0E120F),
    onBackground = Color(0xFFDEE4DD),
    surface = Color(0xFF0E120F),
    onSurface = Color(0xFFDEE4DD),
    surfaceVariant = Color(0xFF404943),
    onSurfaceVariant = Color(0xFFBFC9C1),
    surfaceContainer = Color(0xFF1A1F1B),
    surfaceContainerHigh = Color(0xFF252A26),
    outline = Color(0xFF8A938C),
    outlineVariant = Color(0xFF404943),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

@Composable
fun KeepersTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content
    )
}
