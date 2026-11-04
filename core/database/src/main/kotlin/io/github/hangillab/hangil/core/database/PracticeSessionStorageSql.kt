package io.github.hangillab.hangil.core.database

internal object PracticeSessionStorageSql {
    const val CHECKED_SELECT =
        "SELECT practice_session.*, CASE WHEN " +
            "typeof(rowId) = 'integer' AND rowId > 0 AND typeof(sessionId) = 'text' " +
            "AND typeof(goalId) = 'text' AND typeof(courseId) = 'text' AND typeof(taskId) = 'text' " +
            "AND typeof(goalTitle) = 'text' AND typeof(courseTitle) = 'text' AND typeof(taskTitle) = 'text' " +
            "AND typeof(courseReviewStatus) = 'text' " +
            "AND typeof(courseVersion) = 'integer' AND courseVersion BETWEEN 1 AND 2147483647 " +
            "AND typeof(briefingVersion) = 'integer' AND briefingVersion BETWEEN 1 AND 2147483647 " +
            "AND typeof(startedAtEpochMillis) = 'integer' " +
            "AND (endedAtEpochMillis IS NULL OR typeof(endedAtEpochMillis) = 'integer') " +
            "AND (activeSlot IS NULL OR (typeof(activeSlot) = 'integer' AND activeSlot = 1)) " +
            "THEN 1 ELSE 0 END AS sourceStorageValid FROM practice_session"
}
