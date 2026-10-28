package io.github.hangillab.hangil.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun HanGilTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) HanGilColorSchemes.Dark else HanGilColorSchemes.Light,
        typography = HanGilTypography,
        shapes = HanGilShapes,
        content = content,
    )
}
