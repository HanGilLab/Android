package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingSnapshot
import io.github.hangillab.hangil.core.domain.PracticeSession

sealed interface LocationHistoryUiState {
    val source: PracticeSession
    val token: Token

    data class Token(
        val readerId: Long,
        val queryNonce: Long,
        val revision: Long,
    )

    data class Loading(
        override val source: PracticeSession,
        override val token: Token,
    ) : LocationHistoryUiState

    data class Locked(
        override val source: PracticeSession,
        override val token: Token,
    ) : LocationHistoryUiState

    data class Unavailable(
        override val source: PracticeSession,
        override val token: Token,
    ) : LocationHistoryUiState

    data class Empty(
        override val source: PracticeSession,
        override val token: Token,
    ) : LocationHistoryUiState

    data class Failed(
        override val source: PracticeSession,
        override val token: Token,
        val reason: FailureReason,
    ) : LocationHistoryUiState

    data class Ready(
        override val source: PracticeSession,
        override val token: Token,
        val snapshot: LocationRecordingSnapshot,
        val runIndex: Int,
        val eventIndex: Int,
    ) : LocationHistoryUiState {
        val run: LocationRecordingRun get() = snapshot.runs[runIndex]
        val events: List<LocationRecordingEvent> get() = snapshot.events.filter { it.runId == run.runId }
        val event: LocationRecordingEvent? get() = events.getOrNull(eventIndex)
        val canPreviousRun: Boolean get() = runIndex > 0
        val canNextRun: Boolean get() = runIndex + 1 < snapshot.runs.size
        val canPreviousEvent: Boolean get() = eventIndex > 0
        val canNextEvent: Boolean get() = eventIndex + 1 < events.size
        val hasInterrupted: Boolean get() = snapshot.runs.any { it.status == LocationRecordingRunStatus.INTERRUPTED }
    }

    enum class FailureReason {
        SOURCE_READ,
        SOURCE_STREAM_COMPLETED,
        RECORDING_READ,
        RECORDING_STREAM_COMPLETED,
        INVALID_SNAPSHOT,
        SNAPSHOT_REGRESSED,
        SELECTED_MISSING,
    }
}
