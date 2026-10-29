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
        titleLarge = role(PlatformTypography.titleLarge, 22, 30, weight = FontWeight.SemiBold),
        titleMedium = role(PlatformTypography.titleMedium, 20, 28, weight = FontWeight.Medium),
        titleSmall = role(PlatformTypography.titleSmall, 18, 26, weight = FontWeight.Medium),
        bodyLarge = role(PlatformTypography.bodyLarge, 18, 28),
        bodyMedium = role(PlatformTypography.bodyMedium, 16, 24),
        bodySmall = role(PlatformTypography.bodySmall, 14, 22),
        labelLarge = role(PlatformTypography.labelLarge, 16, 24, weight = FontWeight.SemiBold),
        labelMedium = role(PlatformTypography.labelMedium, 14, 20, weight = FontWeight.Medium),
        labelSmall = role(PlatformTypography.labelSmall, 12, 18, weight = FontWeight.Medium),
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
