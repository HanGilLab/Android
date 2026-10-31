package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation

/** One source-bound reader of stored snapshots; no continuous route or SDK event completeness is implied. */
sealed interface SessionHistoryObservationUiState {
    val source: PracticeSession
    val historyGeneration: Long
    val token: Token

    data class Token(
        val readerId: Long,
        val queryNonce: Long,
        val revision: Long,
    )

    data class Loading(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryObservationUiState

    data class Locked(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryObservationUiState

    /** The captured parent selection or visible host expired; this does not assert a database deletion. */
    data class Unavailable(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryObservationUiState

    data class Empty(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryObservationUiState

    data class Failed(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val reason: FailureReason,
        val selectedRowId: Long?,
    ) : SessionHistoryObservationUiState

    data class Ready(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val observations: List<SessionVehicleObservation>,
        val index: Int,
    ) : SessionHistoryObservationUiState {
        val row: SessionVehicleObservation get() = observations[index]
        val canPrevious: Boolean get() = index > 0
        val canNext: Boolean get() = index + 1 < observations.size
    }

    enum class FailureReason { OBSERVATION_READ, OBSERVATION_STREAM_COMPLETED, INVALID_OBSERVATIONS, SELECTED_ROW_MISSING }
}
