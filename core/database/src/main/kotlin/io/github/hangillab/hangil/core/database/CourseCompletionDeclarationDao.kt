package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val COMPLETION_SELECT =
    "SELECT course_completion_declaration.*, CASE WHEN " +
        "typeof(sessionId) = 'text' AND typeof(goalId) = 'text' AND typeof(courseId) = 'text' " +
        "AND typeof(taskId) = 'text' AND typeof(goalTitle) = 'text' AND typeof(courseTitle) = 'text' " +
        "AND typeof(taskTitle) = 'text' AND typeof(courseReviewStatus) = 'text' " +
        "AND typeof(version) = 'text' AND typeof(origin) = 'text' " +
        "AND typeof(courseVersion) = 'integer' AND courseVersion BETWEEN 1 AND 2147483647 " +
        "AND typeof(briefingVersion) = 'integer' AND briefingVersion BETWEEN 1 AND 2147483647 " +
        "AND typeof(startedAtEpochMillis) = 'integer' AND typeof(endedAtEpochMillis) = 'integer' " +
        "AND typeof(declaredAtEpochMillis) = 'integer' THEN 1 ELSE 0 END AS validStorage " +
        "FROM course_completion_declaration WHERE sessionId = :sessionId"

@Dao
internal interface CourseCompletionDeclarationDao {
    @Query(COMPLETION_SELECT)
    fun observe(sessionId: String): Flow<CourseCompletionDeclarationRow?>

    @Query(COMPLETION_SELECT)
    suspend fun find(sessionId: String): CourseCompletionDeclarationRow?

    // ABORT is intentional. Only the transaction's existing-row path is idempotent.
    @Insert
    suspend fun insert(entity: CourseCompletionDeclarationEntity)
}
