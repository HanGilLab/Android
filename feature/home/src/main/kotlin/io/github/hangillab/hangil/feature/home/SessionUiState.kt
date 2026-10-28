package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.Preparation

data class SessionUiState(
    val allowsInteraction: Boolean = false,
    val preparation: PreparationLoadState = PreparationLoadState.Loading,
    val records: SessionLoadState = SessionLoadState.Loading,
    val operation: SessionOperationState = SessionOperationState.Idle,
) {
    val active get() = (records as? SessionLoadState.Loaded)?.sessions?.firstOrNull { it.isActive }
    val latestFinished get() = (records as? SessionLoadState.Loaded)?.sessions?.firstOrNull { !it.isActive }
    val ready get() = ((preparation as? PreparationLoadState.Loaded)?.preparation as? Preparation.Ready)?.practice
    val nextStep: HomeJourneyStep
        get() {
            if (!allowsInteraction) return HomeJourneyStep.LOCKED
            return when (records) {
                SessionLoadState.Loading -> HomeJourneyStep.LOADING
                SessionLoadState.Failed -> HomeJourneyStep.RECOVER
                is SessionLoadState.Loaded ->
                    when {
                        active != null -> HomeJourneyStep.ACTIVE
                        preparation == PreparationLoadState.Loading -> HomeJourneyStep.LOADING
                        preparation == PreparationLoadState.Failed -> HomeJourneyStep.RECOVER
                        ready == null -> HomeJourneyStep.PREPARE
                        latestFinished != null -> HomeJourneyStep.REPEAT
                        else -> HomeJourneyStep.READY
                    }
            }
        }
    val canStart get() =
        allowsInteraction && records is SessionLoadState.Loaded && active == null && ready != null &&
            operation != SessionOperationState.Saving
    val canFinish get() =
        allowsInteraction && records is SessionLoadState.Loaded && active != null &&
            operation != SessionOperationState.Saving
}
