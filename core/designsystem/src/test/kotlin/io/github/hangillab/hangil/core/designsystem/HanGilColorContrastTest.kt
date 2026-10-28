package io.github.hangillab.hangil.core.designsystem

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.contentColorFor
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HanGilColorContrastTest {
    @Test fun `normal helper and error text remain readable on app surfaces in either mode`() {
        for ((mode, scheme) in schemes()) {
            assertContrast("$mode background", scheme.onBackground, scheme.background, 4.5f)
            for ((name, surface) in surfaces(scheme)) {
                assertEquals("$mode $name resolves its body color", scheme.onSurface, scheme.contentColorFor(surface))
                assertContrast("$mode $name body", scheme.onSurface, surface, 4.5f)
                assertContrast("$mode $name helper", scheme.onSurfaceVariant, surface, 4.5f)
                assertContrast("$mode $name error", scheme.error, surface, 4.5f)
            }
        }
    }

    @Test fun `action badge selection and status color pairs preserve readable text in either mode`() {
        for ((mode, scheme) in schemes()) {
            assertEquals("$mode primary fixed content", scheme.onPrimaryFixed, scheme.contentColorFor(scheme.primaryFixed))
            assertEquals("$mode secondary fixed content", scheme.onSecondaryFixed, scheme.contentColorFor(scheme.secondaryFixed))
            assertEquals("$mode tertiary fixed content", scheme.onTertiaryFixed, scheme.contentColorFor(scheme.tertiaryFixed))
            val pairs =
                listOf(
                    "primary action" to (scheme.onPrimary to scheme.primary),
                    "secondary action" to (scheme.onSecondary to scheme.secondary),
                    "tertiary action" to (scheme.onTertiary to scheme.tertiary),
                    "selected choice" to (scheme.onPrimaryContainer to scheme.primaryContainer),
                    "disabled selected explanation" to (scheme.onSurfaceVariant to scheme.primaryContainer),
                    "badge" to (scheme.onSecondaryContainer to scheme.secondaryContainer),
                    "tertiary container" to (scheme.onTertiaryContainer to scheme.tertiaryContainer),
                    "error" to (scheme.onError to scheme.error),
                    "error container" to (scheme.onErrorContainer to scheme.errorContainer),
                    "error action" to (scheme.primary to scheme.errorContainer),
                    "inverse surface" to (scheme.inverseOnSurface to scheme.inverseSurface),
                    "primary fixed" to (scheme.onPrimaryFixed to scheme.primaryFixed),
                    "primary fixed dim" to (scheme.onPrimaryFixedVariant to scheme.primaryFixedDim),
                    "secondary fixed" to (scheme.onSecondaryFixed to scheme.secondaryFixed),
                    "secondary fixed dim" to (scheme.onSecondaryFixedVariant to scheme.secondaryFixedDim),
                    "tertiary fixed" to (scheme.onTertiaryFixed to scheme.tertiaryFixed),
                    "tertiary fixed dim" to (scheme.onTertiaryFixedVariant to scheme.tertiaryFixedDim),
                )
            for ((name, pair) in pairs) assertContrast("$mode $name", pair.first, pair.second, 4.5f)
        }
    }

    @Test fun `selected and unselected choice markers remain distinguishable in either mode`() {
        for ((mode, scheme) in schemes()) {
            assertContrast("$mode selected marker", scheme.primary, scheme.primaryContainer, 3f)
            assertContrast("$mode unselected marker", scheme.primary, scheme.surfaceContainerLow, 3f)
        }
    }

    private fun schemes() = listOf("light" to HanGilColorSchemes.Light, "dark" to HanGilColorSchemes.Dark)

    private fun surfaces(scheme: ColorScheme) =
        listOf(
            "surface" to scheme.surface,
            "panel" to scheme.surfaceContainerLow,
            "container" to scheme.surfaceContainer,
            "high container" to scheme.surfaceContainerHigh,
            "diagnostic card" to scheme.surfaceContainerHighest,
            "bright surface" to scheme.surfaceBright,
            "lowest surface" to scheme.surfaceContainerLowest,
            "dim surface" to scheme.surfaceDim,
        )

    private fun assertContrast(
        name: String,
        foreground: Color,
        background: Color,
        minimum: Float,
    ) {
        assertTrue("$name needs an opaque app background for contrast evaluation", background.alpha == 1f)
        val renderedForeground = foreground.compositeOver(background)
        val lighter = maxOf(renderedForeground.luminance(), background.luminance())
        val darker = minOf(renderedForeground.luminance(), background.luminance())
        val actual = (lighter + 0.05f) / (darker + 0.05f)
        assertTrue("$name contrast $actual must be at least $minimum", actual >= minimum)
    }
}
