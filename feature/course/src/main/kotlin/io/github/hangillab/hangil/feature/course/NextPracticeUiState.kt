package io.github.hangillab.hangil.feature.course

import io.github.hangillab.hangil.core.domain.NextPracticeSeed

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
        val saving: Boolean = false,
        val saveFailed: Boolean = false,
    ) : NextPracticeUiState {
        val canSave: Boolean get() =
            !saving && taskId != null &&
                seed.practice.course.tasks
                    .count { it.id == taskId && it.id.isNotBlank() && it.title.isNotBlank() } == 1
    }

    enum class FailureReason { ACCESS_READ, ACCESS_STREAM_COMPLETED, SESSION_READ, SESSION_STREAM_COMPLETED, INVALID_SESSIONS }
}
