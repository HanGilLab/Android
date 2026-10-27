package io.github.hangillab.hangil.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors =
    lightColorScheme(
        primary = Color(0xFF126B59),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFD5EFE5),
        onPrimaryContainer = Color(0xFF073D31),
        secondaryContainer = Color(0xFFE3EDE6),
        onSecondaryContainer = Color(0xFF29473A),
        onBackground = Color(0xFF172E28),
        onSurface = Color(0xFF172E28),
        surfaceContainerLow = Color(0xFFEDF3ED),
        outlineVariant = Color(0xFFCCD8CF),
        secondary = Color(0xFF51675E),
        background = Color(0xFFF7FAF6),
        surface = Color(0xFFF7FAF6),
    )
private val DarkColors = darkColorScheme(primary = Color(0xFF9FD3BC), secondary = Color(0xFFB8CCC0))

@Composable
fun HanGilTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
