package io.github.hangillab.hangil.feature.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import io.github.hangillab.hangil.core.domain.Preparation

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
    Text(
        stringResource(title),
        Modifier.testTag("home_step_${step.name.lowercase(java.util.Locale.ROOT)}"),
        style = MaterialTheme.typography.headlineMedium,
    )
    Text(stringResource(R.string.journey_flow), Modifier.testTag("home_journey_flow"), style = MaterialTheme.typography.titleLarge)
    Text(stringResource(R.string.journey_scope), style = MaterialTheme.typography.bodyLarge)
    if (step == HomeJourneyStep.REPEAT) {
        Text(stringResource(R.string.journey_repeat_hint), style = MaterialTheme.typography.bodyLarge)
    }
}
