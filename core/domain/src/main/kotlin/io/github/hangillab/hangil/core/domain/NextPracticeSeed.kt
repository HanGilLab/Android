package io.github.hangillab.hangil.core.domain

/** A defensive snapshot of an ended session and the exact current catalog it was resolved against. */
data class NextPracticeSeed private constructor(
    val source: PracticeSession,
    val practice: PreparedPractice,
) {
    companion object {
        fun resolve(
            catalog: PracticeCatalog,
            source: PracticeSession,
        ): NextPracticeSeed? {
            val selection = source.selection
            val endedAt = source.endedAtEpochMillis ?: return null
            if (source.sessionId.isBlank() || source.startedAtEpochMillis <= 0 || endedAt < source.startedAtEpochMillis ||
                selection.goalId.isBlank() || selection.courseId.isBlank() || selection.taskId.isBlank() ||
                selection.courseVersion <= 0 || selection.briefingVersion <= 0 || source.goalTitle.isBlank() ||
                source.courseTitle.isBlank() || source.taskTitle.isBlank()
            ) {
                return null
            }
            val goal = catalog.goals.singleOrNull { it.id == selection.goalId } ?: return null
            val current = catalog.courses.singleOrNull { it.id == selection.courseId } ?: return null
            val course =
                current.copy(
                    sections = current.sections.toList(),
                    tasks = current.tasks.toList(),
                    goalIds = current.goalIds.toSet(),
                    mapPreview = current.mapPreview?.let { it.copy(points = it.points.toList()) },
                )
            val task = course.tasks.singleOrNull { it.id == selection.taskId } ?: return null
            if (goal.id !in course.goalIds || course.version != selection.courseVersion ||
                course.briefingVersion != selection.briefingVersion || goal.title != source.goalTitle ||
                course.title != source.courseTitle || task.title != source.taskTitle || course.reviewStatus != source.courseReviewStatus
            ) {
                return null
            }
            return NextPracticeSeed(source, PreparedPractice(goal, course, task))
        }
    }
}
