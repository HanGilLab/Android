package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface PracticeSessionDao {
    @Query("SELECT * FROM practice_session ORDER BY rowId DESC")
    fun observe(): Flow<List<PracticeSessionEntity>>

    @Query("SELECT * FROM practice_session WHERE activeSlot = 1 LIMIT 1")
    suspend fun active(): PracticeSessionEntity?

    @Query("SELECT COUNT(*) FROM practice_session WHERE endedAtEpochMillis IS NULL OR activeSlot IS NOT NULL")
    suspend fun unfinishedCount(): Long

    @Query("SELECT * FROM practice_session WHERE endedAtEpochMillis IS NOT NULL ORDER BY rowId DESC LIMIT 1")
    suspend fun latestFinished(): PracticeSessionEntity?

    @Query("SELECT * FROM practice_session WHERE sessionId = :sessionId")
    suspend fun find(sessionId: String): PracticeSessionEntity?

    @Query(
        "SELECT COUNT(*) FROM practice_session WHERE sessionId = :sessionId " +
            "AND typeof(rowId) = 'integer' AND rowId > 0 AND typeof(sessionId) = 'text' " +
            "AND typeof(goalId) = 'text' AND typeof(courseId) = 'text' AND typeof(taskId) = 'text' " +
            "AND typeof(goalTitle) = 'text' AND typeof(courseTitle) = 'text' AND typeof(taskTitle) = 'text' " +
            "AND typeof(courseReviewStatus) = 'text' " +
            "AND typeof(courseVersion) = 'integer' AND courseVersion BETWEEN 1 AND 2147483647 " +
            "AND typeof(briefingVersion) = 'integer' AND briefingVersion BETWEEN 1 AND 2147483647 " +
            "AND typeof(startedAtEpochMillis) = 'integer' " +
            "AND (endedAtEpochMillis IS NULL OR typeof(endedAtEpochMillis) = 'integer') " +
            "AND (activeSlot IS NULL OR (typeof(activeSlot) = 'integer' AND activeSlot = 1))",
    )
    suspend fun countValidDeletionStorage(sessionId: String): Int

    @Insert
    suspend fun insert(entity: PracticeSessionEntity)

    @Query(
        "UPDATE practice_session SET endedAtEpochMillis = :endedAtEpochMillis, activeSlot = NULL " +
            "WHERE sessionId = :sessionId AND activeSlot = 1 AND endedAtEpochMillis IS NULL",
    )
    suspend fun finish(
        sessionId: String,
        endedAtEpochMillis: Long,
    ): Int

    @Query("DELETE FROM practice_session WHERE sessionId = :sessionId AND endedAtEpochMillis IS NOT NULL AND activeSlot IS NULL")
    suspend fun deleteFinished(sessionId: String): Int
}
