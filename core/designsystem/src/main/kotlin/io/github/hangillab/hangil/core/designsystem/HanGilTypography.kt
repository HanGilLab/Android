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
        headlineLarge = role(PlatformTypography.headlineLarge, 36, 46),
        headlineMedium = role(PlatformTypography.headlineMedium, 32, 42),
        headlineSmall = role(PlatformTypography.headlineSmall, 28, 38),
        titleLarge = role(PlatformTypography.titleLarge, 28, 38, weight = FontWeight.SemiBold),
        titleMedium = role(PlatformTypography.titleMedium, 24, 34, weight = FontWeight.Medium),
        titleSmall = role(PlatformTypography.titleSmall, 22, 32, weight = FontWeight.Medium),
        bodyLarge = role(PlatformTypography.bodyLarge, 24, 34),
        bodyMedium = role(PlatformTypography.bodyMedium, 22, 32),
        bodySmall = role(PlatformTypography.bodySmall, 18, 26),
        labelLarge = role(PlatformTypography.labelLarge, 20, 28, weight = FontWeight.SemiBold),
        labelMedium = role(PlatformTypography.labelMedium, 18, 26, weight = FontWeight.Medium),
        labelSmall = role(PlatformTypography.labelSmall, 18, 26, weight = FontWeight.Medium),
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
