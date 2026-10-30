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

    @Query("SELECT * FROM practice_session WHERE endedAtEpochMillis IS NOT NULL ORDER BY rowId DESC LIMIT 1")
    suspend fun latestFinished(): PracticeSessionEntity?

    @Query("SELECT * FROM practice_session WHERE sessionId = :sessionId")
    suspend fun find(sessionId: String): PracticeSessionEntity?

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
}
