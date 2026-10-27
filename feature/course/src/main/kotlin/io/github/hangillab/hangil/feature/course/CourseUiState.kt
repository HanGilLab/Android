package io.github.hangillab.hangil.feature.course

import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal

internal data class CourseUiState(
    val allowsInteraction: Boolean = false,
    val step: PreparationStep = PreparationStep.GOAL,
    val goals: List<PracticeGoal> = emptyList(),
    val courses: List<PracticeCourse> = emptyList(),
    val goal: PracticeGoal? = null,
    val course: PracticeCourse? = null,
    val taskId: String? = null,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
) {
    val canContinue: Boolean get() =
        allowsInteraction && !saving &&
            when (step) {
                PreparationStep.GOAL -> goal != null
                PreparationStep.COURSE -> course != null
                PreparationStep.BRIEFING -> course?.tasks?.any { it.id == taskId } == true
            }
}
