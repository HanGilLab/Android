package io.github.hangillab.hangil.feature.course

import io.github.hangillab.hangil.core.domain.PracticeSelection

internal data class PreparationDraft(
    val step: PreparationStep = PreparationStep.GOAL,
    val goalId: String? = null,
    val courseId: String? = null,
    val courseVersion: Int? = null,
    val briefingVersion: Int? = null,
    val taskId: String? = null,
) {
    fun selection(): PracticeSelection? {
        return PracticeSelection(
            goalId ?: return null,
            courseId ?: return null,
            courseVersion ?: return null,
            briefingVersion ?: return null,
            taskId ?: return null,
        )
    }
}
