package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.SessionVehicleObservation

/** Read-only snapshots for the requested finished session, never a complete route or event count. */
sealed interface RecentVehicleObservationUiState {
    val sessionId: String

    data class Locked(
        override val sessionId: String,
    ) : RecentVehicleObservationUiState

    data class Loading(
        override val sessionId: String,
    ) : RecentVehicleObservationUiState

    data class SessionChanged(
        override val sessionId: String,
    ) : RecentVehicleObservationUiState

    data class Empty(
        override val sessionId: String,
    ) : RecentVehicleObservationUiState

    data class Ready(
        override val sessionId: String,
        val observations: List<SessionVehicleObservation>,
    ) : RecentVehicleObservationUiState

    data class Failed(
        override val sessionId: String,
        val reason: FailureReason,
    ) : RecentVehicleObservationUiState

    enum class FailureReason {
        ACCESS_READ,
        ACCESS_STREAM_COMPLETED,
        SESSION_READ,
        SESSION_STREAM_COMPLETED,
        OBSERVATION_READ,
        OBSERVATION_STREAM_COMPLETED,
        INVALID_OBSERVATIONS,
    }
}
