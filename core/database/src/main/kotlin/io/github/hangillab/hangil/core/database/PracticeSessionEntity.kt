package io.github.hangillab.hangil.core.database

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession

@Entity(
    tableName = "practice_session",
    indices = [Index(value = ["sessionId"], unique = true), Index(value = ["activeSlot"], unique = true)],
)
internal data class PracticeSessionEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val sessionId: String,
    val goalId: String,
    val courseId: String,
    val courseVersion: Int,
    val briefingVersion: Int,
    val taskId: String,
    val goalTitle: String,
    val courseTitle: String,
    val taskTitle: String,
    val courseReviewStatus: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    // A unique nullable slot allows many finished sessions and at most one active session.
    val activeSlot: Int?,
) {
    fun session(): PracticeSession {
        check(activeSlot == if (endedAtEpochMillis == null) 1 else null)
        check(startedAtEpochMillis > 0 && (endedAtEpochMillis == null || endedAtEpochMillis >= startedAtEpochMillis))
        return PracticeSession(
            sessionId = sessionId,
            selection = PracticeSelection(goalId, courseId, courseVersion, briefingVersion, taskId),
            goalTitle = goalTitle,
            courseTitle = courseTitle,
            taskTitle = taskTitle,
            courseReviewStatus = CourseReviewStatus.valueOf(courseReviewStatus),
            startedAtEpochMillis = startedAtEpochMillis,
            endedAtEpochMillis = endedAtEpochMillis,
        )
    }
}
