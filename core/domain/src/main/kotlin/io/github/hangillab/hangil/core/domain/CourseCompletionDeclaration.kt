package io.github.hangillab.hangil.core.domain

/** A user's declaration for one ended button record, never course accreditation or driving ability. */
data class CourseCompletionDeclaration(
    val source: PracticeSession,
    val version: String,
    val declaredAtEpochMillis: Long,
    val origin: CourseCompletionOrigin,
) {
    init {
        val selection = source.selection
        require(
            source.sessionId.isNotBlank() && selection.goalId.isNotBlank() &&
                selection.courseId.isNotBlank() && selection.taskId.isNotBlank() &&
                selection.courseVersion > 0 && selection.briefingVersion > 0 &&
                source.goalTitle.isNotBlank() && source.courseTitle.isNotBlank() && source.taskTitle.isNotBlank() &&
                source.startedAtEpochMillis > 0 && source.endedAtEpochMillis != null &&
                source.endedAtEpochMillis >= source.startedAtEpochMillis,
        ) { "Invalid completion declaration source" }
        require(version.isNotBlank()) { "Invalid completion declaration version" }
        // A wall clock can move backwards. This does not establish a learning interval.
        require(declaredAtEpochMillis > 0) { "Invalid completion declaration time" }
    }
}
