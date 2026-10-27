package io.github.hangillab.hangil.feature.home

sealed interface SessionOperationState {
    data object Idle : SessionOperationState

    data object Saving : SessionOperationState

    data class Feedback(
        val value: SessionFeedback,
    ) : SessionOperationState
}
