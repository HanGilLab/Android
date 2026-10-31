package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.NavigationScreenResult

data class NavigationScreenUiState(
    val host: Long = 0,
    val revision: Long = 0,
    val phase: Phase = Phase.CLOSED,
    val result: NavigationScreenResult? = null,
) {
    enum class Phase { CLOSED, IDLE, OPENING, LOCKED, RESULT }
}
