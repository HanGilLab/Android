package io.github.hangillab.hangil.core.domain

/** Button action times and the preparation snapshot; no location or vehicle events are implied. */
data class PracticeSession(
    val sessionId: String,
    val selection: PracticeSelection,
    val goalTitle: String,
    val courseTitle: String,
    val taskTitle: String,
    val courseReviewStatus: CourseReviewStatus,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
) {
    val isActive: Boolean get() = endedAtEpochMillis == null
}
