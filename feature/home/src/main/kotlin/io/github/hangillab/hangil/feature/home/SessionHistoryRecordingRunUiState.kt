package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun

/** Stored app execution markers, never an inferred SDK capture interval or live service state. */
sealed interface SessionHistoryRecordingRunUiState {
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
    ) : SessionHistoryRecordingRunUiState

    data class Locked(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryRecordingRunUiState

    /** The captured parent selection or visible host expired; this does not assert a database deletion. */
    data class Unavailable(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryRecordingRunUiState

    data class Empty(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
    ) : SessionHistoryRecordingRunUiState

    data class Failed(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val reason: FailureReason,
        val selectedRunId: String?,
    ) : SessionHistoryRecordingRunUiState

    data class Ready(
        override val source: PracticeSession,
        override val historyGeneration: Long,
        override val token: Token,
        val runs: List<VehicleRecordingRun>,
        val index: Int,
    ) : SessionHistoryRecordingRunUiState {
        val run: VehicleRecordingRun get() = runs[index]
        val canPrevious: Boolean get() = index > 0
        val canNext: Boolean get() = index + 1 < runs.size
        val hasInterrupted: Boolean get() = runs.any { it.status == RecordingRunStatus.INTERRUPTED }
    }

    enum class FailureReason { RUN_READ, RUN_STREAM_COMPLETED, INVALID_RUNS, SELECTED_RUN_MISSING }
}
