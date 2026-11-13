package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val UNFINISHED_PREDICATE = "endedAtEpochMillis IS NULL OR activeSlot IS NOT NULL"

@Dao
internal interface PracticeSessionDao {
    @Query("SELECT * FROM practice_session ORDER BY rowId DESC")
    fun observe(): Flow<List<PracticeSessionEntity>>

    @Query(PracticeSessionStorageSql.CHECKED_SELECT + " ORDER BY rowId DESC")
    fun observeChecked(): Flow<List<PracticeSessionRow>>

    @Query(PracticeSessionStorageSql.CHECKED_SELECT + " WHERE sessionId = :sessionId")
    fun observeSessionChecked(sessionId: String): Flow<PracticeSessionRow?>

    @Query(PracticeSessionStorageSql.CHECKED_SELECT + " WHERE sessionId = :sessionId")
    suspend fun findChecked(sessionId: String): PracticeSessionRow?

    @Query(PracticeSessionStorageSql.CHECKED_SELECT + " WHERE " + UNFINISHED_PREDICATE)
    fun observeUnfinishedChecked(): Flow<List<PracticeSessionRow>>

    @Query(PracticeSessionStorageSql.CHECKED_SELECT + " WHERE activeSlot = 1 LIMIT 1")
    suspend fun activeChecked(): PracticeSessionRow?

    @Query("SELECT * FROM practice_session WHERE activeSlot = 1 LIMIT 1")
    suspend fun active(): PracticeSessionEntity?

    @Query("SELECT COUNT(*) FROM practice_session WHERE " + UNFINISHED_PREDICATE)
    suspend fun unfinishedCount(): Long

    @Query("SELECT * FROM practice_session WHERE endedAtEpochMillis IS NOT NULL ORDER BY rowId DESC LIMIT 1")
    suspend fun latestFinished(): PracticeSessionEntity?

    @Query("SELECT * FROM practice_session WHERE sessionId = :sessionId")
    suspend fun find(sessionId: String): PracticeSessionEntity?

    @Query(
        "SELECT COUNT(*) FROM practice_session WHERE sessionId = :sessionId AND " +
            PracticeSessionStorageSql.VALID_STORAGE,
    )
    suspend fun countValidDeletionStorage(sessionId: String): Int

    @Query(
        "SELECT COUNT(*) FROM practice_session WHERE rowId = :rowId AND sessionId = :sessionId AND " +
            PracticeSessionStorageSql.VALID_STORAGE,
    )
    suspend fun countValidNextPracticeSourceStorage(
        rowId: Long,
        sessionId: String,
    ): Int

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
