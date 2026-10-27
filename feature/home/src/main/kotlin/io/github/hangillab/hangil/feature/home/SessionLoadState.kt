package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession

sealed interface SessionLoadState {
    data object Loading : SessionLoadState

    data object Failed : SessionLoadState

    data class Loaded(
        val sessions: List<PracticeSession>,
    ) : SessionLoadState
}
