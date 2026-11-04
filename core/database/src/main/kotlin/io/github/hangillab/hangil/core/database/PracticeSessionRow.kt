package io.github.hangillab.hangil.core.database

import androidx.room.Embedded
import io.github.hangillab.hangil.core.domain.PracticeSession

/** The original storage verdict and model come from the same SQLite statement. */
internal data class PracticeSessionRow(
    @Embedded val entity: PracticeSessionEntity,
    val sourceStorageValid: Long,
) {
    fun session(): PracticeSession {
        check(sourceStorageValid == 1L) { "Invalid stored session storage" }
        val source = entity.session()
        val selection = source.selection
        check(
            source.sessionId.isNotBlank() && selection.goalId.isNotBlank() && selection.courseId.isNotBlank() &&
                selection.taskId.isNotBlank() && source.goalTitle.isNotBlank() && source.courseTitle.isNotBlank() &&
                source.taskTitle.isNotBlank(),
        ) { "Invalid stored session snapshot" }
        return source
    }
}
