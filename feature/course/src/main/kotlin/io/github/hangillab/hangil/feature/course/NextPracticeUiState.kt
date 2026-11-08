package io.github.hangillab.hangil.feature.course

import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.SessionCourseSummary
import io.github.hangillab.hangil.core.domain.SessionReviewRoad

sealed interface NextPracticeUiState {
    data object Locked : NextPracticeUiState

    data object Loading : NextPracticeUiState

    data class SourceChanged(
        val generation: Long,
    ) : NextPracticeUiState

    data class Unavailable(
        val generation: Long,
    ) : NextPracticeUiState

    data class Failed(
        val reason: FailureReason,
        val generation: Long,
    ) : NextPracticeUiState

    data class Ready(
        val seed: NextPracticeSeed,
        val taskId: String?,
        val generation: Long,
        val summary: SessionCourseSummary,
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
        val reviewRoad: ReviewRoad = ReviewRoad.Loading,
        val targetReview: SessionReviewRoad? = null,
        val targetChoiceRequired: Boolean = false,
    ) : NextPracticeUiState {
        val canSave: Boolean get() =
            !saving && !targetChoiceRequired && taskId != null &&
                seed.practice.course.tasks
                    .count { it.id == taskId && it.id.isNotBlank() && it.title.isNotBlank() } == 1
    }

    sealed interface ReviewRoad {
        data object Loading : ReviewRoad

        data object Absent : ReviewRoad

        data object Failed : ReviewRoad

        data class Available(
            val review: SessionReviewRoad,
        ) : ReviewRoad
    }

    enum class FailureReason { ACCESS_READ, ACCESS_STREAM_COMPLETED, SESSION_READ, SESSION_STREAM_COMPLETED, INVALID_SESSIONS }
}
