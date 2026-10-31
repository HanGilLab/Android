package io.github.hangillab.hangil.core.domain

/** Observation activity, not proof of complete SDK event delivery or a saved route. */
sealed interface VehicleRecordingState {
    data object Paused : VehicleRecordingState

    data object Loading : VehicleRecordingState

    data object Idle : VehicleRecordingState

    data object Recording : VehicleRecordingState

    data class Failed(
        val reason: Reason,
    ) : VehicleRecordingState

    enum class Reason {
        SESSION_READ,
        SESSION_MISSING,
        SESSION_FINISHED,
        INVALID_TIME,
        WRITE,
        OBSERVATION,
        ALREADY_RUNNING,
    }
}
