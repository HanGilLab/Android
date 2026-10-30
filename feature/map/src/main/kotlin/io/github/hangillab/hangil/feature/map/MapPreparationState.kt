package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.PreparedPractice

sealed interface MapPreparationState {
    data object Locked : MapPreparationState

    data object Loading : MapPreparationState

    data object Empty : MapPreparationState

    data object Outdated : MapPreparationState

    data class Unavailable(
        val prepared: PreparedPractice,
    ) : MapPreparationState

    data class Ready(
        val prepared: PreparedPractice,
        val preview: CourseMapPreview,
    ) : MapPreparationState

    data class Failed(
        val reason: FailureReason,
    ) : MapPreparationState

    enum class FailureReason { Read, StreamCompleted, InvalidPreparation, AccessRead, AccessStreamCompleted }
}
