package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionCourseSummary

/** One ended session snapshot in repository-created order; index zero is the newest created. */
sealed interface SessionHistoryUiState {
    data object Locked : SessionHistoryUiState

    data object Loading : SessionHistoryUiState

    data object Empty : SessionHistoryUiState

    data object Unavailable : SessionHistoryUiState

    data class SelectionChanged(
        val generation: Long,
    ) : SessionHistoryUiState

    data class Failed(
        val reason: FailureReason,
    ) : SessionHistoryUiState

    data class Ready(
        val session: PracticeSession,
        val index: Int,
        val total: Int,
        val generation: Long,
        val summary: SessionCourseSummary,
    ) : SessionHistoryUiState {
        val canOlder: Boolean get() = index + 1 < total
        val canNewer: Boolean get() = index > 0
    }

    enum class FailureReason { ACCESS_READ, ACCESS_STREAM_COMPLETED, SESSION_READ, SESSION_STREAM_COMPLETED, INVALID_SESSIONS }
}
