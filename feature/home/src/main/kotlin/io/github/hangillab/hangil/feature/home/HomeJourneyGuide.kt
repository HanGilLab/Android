package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.designsystem.R as DesignR

@Composable
internal fun HomeJourneyGuide(state: SessionUiState) {
    val step = state.nextStep
    if (step == HomeJourneyStep.LOCKED) return
    val outdated = (state.preparation as? PreparationLoadState.Loaded)?.preparation == Preparation.Outdated
    val title =
        when (step) {
            HomeJourneyStep.PREPARE ->
                when {
                    outdated -> R.string.journey_outdated
                    state.latestFinished != null -> R.string.journey_prepare_after_history
                    else -> R.string.journey_first
                }
            HomeJourneyStep.READY -> R.string.journey_ready
            HomeJourneyStep.ACTIVE -> R.string.journey_active
            HomeJourneyStep.REPEAT -> R.string.journey_repeat
            HomeJourneyStep.LOADING -> R.string.journey_loading
            HomeJourneyStep.RECOVER -> R.string.journey_recover
            HomeJourneyStep.LOCKED -> return
        }
    val fontScale = LocalDensity.current.fontScale
    val showScene = step == HomeJourneyStep.PREPARE || step == HomeJourneyStep.READY || step == HomeJourneyStep.REPEAT
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val showInlineScene = showScene && maxWidth >= HanGilDimensions.wideBreakpoint / 2 * fontScale
        Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.sm)) {
            if (showInlineScene) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HomeJourneyTitle(title, step, Modifier.weight(1f))
                    Image(
                        painterResource(DesignR.drawable.il_hangil_prepare),
                        contentDescription = null,
                        modifier =
                            Modifier.width(HanGilDimensions.parkedIllustrationWidth).aspectRatio(1.5f).testTag("home_illustration"),
                    )
                }
            } else {
                HomeJourneyTitle(title, step)
            }
            Text(
                stringResource(R.string.journey_flow),
                Modifier.testTag("home_journey_flow"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )
            Text(
                stringResource(R.string.journey_scope),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (step == HomeJourneyStep.REPEAT) {
                Text(
                    stringResource(R.string.journey_repeat_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun HomeJourneyTitle(
    title: Int,
    step: HomeJourneyStep,
    modifier: Modifier = Modifier,
) {
    Text(
        stringResource(title),
        modifier.testTag("home_step_${step.name.lowercase(java.util.Locale.ROOT)}"),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
    )
}
