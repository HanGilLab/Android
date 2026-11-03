package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.LocationRecordingState
import io.github.hangillab.hangil.core.domain.PracticeSession

sealed interface LocationRecordingUiState {
    data object Loading : LocationRecordingUiState

    data class Locked(
        val recording: LocationRecordingState,
    ) : LocationRecordingUiState

    data class NoActive(
        val recording: LocationRecordingState,
    ) : LocationRecordingUiState

    data class Failed(
        val recording: LocationRecordingState,
    ) : LocationRecordingUiState

    data class Ready(
        val source: PracticeSession,
        val revision: Long,
        val permission: LocationPermissionState,
        val recording: LocationRecordingState,
    ) : LocationRecordingUiState {
        override fun toString(): String = "Ready(source=redacted, revision=$revision, permission=$permission)"
    }
}
