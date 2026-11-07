package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.PreparedPractice

sealed interface MapPreparationState {
    data object Locked : MapPreparationState

    data object Loading : MapPreparationState

    data object Empty : MapPreparationState

    data object Outdated : MapPreparationState

    data class Unavailable(
        val prepared: PreparedPractice,
        val reason: UnavailableReason = UnavailableReason.NO_PREVIEW,
    ) : MapPreparationState

    data class Ready(
        val prepared: PreparedPractice,
        val preview: MapPreview,
    ) : MapPreparationState

    data class Failed(
        val reason: FailureReason,
    ) : MapPreparationState

    enum class FailureReason { Read, StreamCompleted, InvalidPreparation, AccessRead, AccessStreamCompleted }

    enum class UnavailableReason { NO_PREVIEW, OUTSIDE_MAP_RANGE }
}
