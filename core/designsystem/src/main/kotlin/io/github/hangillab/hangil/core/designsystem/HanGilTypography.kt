package io.github.hangillab.hangil.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Preserve Material 3's platform font padding and line-height alignment while fixing the metrics.
private val PlatformTypography = Typography()

val HanGilTypography =
    Typography(
        displayLarge = role(PlatformTypography.displayLarge, 57, 64, -0.2f),
        displayMedium = role(PlatformTypography.displayMedium, 45, 52),
        displaySmall = role(PlatformTypography.displaySmall, 36, 44),
        headlineLarge = role(PlatformTypography.headlineLarge, 32, 40),
        headlineMedium = role(PlatformTypography.headlineMedium, 28, 36),
        headlineSmall = role(PlatformTypography.headlineSmall, 24, 32),
        titleLarge = role(PlatformTypography.titleLarge, 22, 28),
        titleMedium = role(PlatformTypography.titleMedium, 16, 24, 0.2f, FontWeight.Medium),
        titleSmall = role(PlatformTypography.titleSmall, 14, 20, 0.1f, FontWeight.Medium),
        bodyLarge = role(PlatformTypography.bodyLarge, 16, 24, 0.5f),
        bodyMedium = role(PlatformTypography.bodyMedium, 14, 20, 0.2f),
        bodySmall = role(PlatformTypography.bodySmall, 12, 16, 0.4f),
        labelLarge = role(PlatformTypography.labelLarge, 14, 20, 0.1f, FontWeight.Medium),
        labelMedium = role(PlatformTypography.labelMedium, 12, 16, 0.5f, FontWeight.Medium),
        labelSmall = role(PlatformTypography.labelSmall, 11, 16, 0.5f, FontWeight.Medium),
    )

private fun role(
    platformStyle: TextStyle,
    size: Int,
    lineHeight: Int,
    tracking: Float = 0f,
    weight: FontWeight = FontWeight.Normal,
): TextStyle =
    platformStyle.copy(
        fontFamily = FontFamily.SansSerif,
        fontWeight = weight,
        fontSize = size.sp,
        lineHeight = lineHeight.sp,
        letterSpacing = tracking.sp,
    )
