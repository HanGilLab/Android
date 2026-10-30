package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface SessionMemoDao {
    @Query(
        "SELECT session_memo.*, typeof(sessionId) AS sessionStorageType, " +
            "typeof(text) AS textStorageType, typeof(version) AS versionStorageType " +
            "FROM session_memo WHERE sessionId = :sessionId",
    )
    fun observe(sessionId: String): Flow<SessionMemoRow?>

    @Query(
        "SELECT session_memo.*, typeof(sessionId) AS sessionStorageType, " +
            "typeof(text) AS textStorageType, typeof(version) AS versionStorageType " +
            "FROM session_memo WHERE sessionId = :sessionId",
    )
    suspend fun find(sessionId: String): SessionMemoRow?

    @Insert
    suspend fun insert(entity: SessionMemoEntity)

    @Query("UPDATE session_memo SET text = :text, version = :version WHERE sessionId = :sessionId AND version = :expectedVersion")
    suspend fun update(
        sessionId: String,
        text: String,
        version: String,
        expectedVersion: String,
    ): Int

    @Query("DELETE FROM session_memo WHERE sessionId = :sessionId AND version = :expectedVersion")
    suspend fun delete(
        sessionId: String,
        expectedVersion: String,
    ): Int
}
