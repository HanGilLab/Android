package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface VehicleRecordingRunDao {
    @Query(
        "SELECT vehicle_recording_run.*, typeof(runId) AS runStorageType, typeof(sessionId) AS sessionStorageType, " +
            "typeof(processId) AS processStorageType, typeof(startedAtEpochMillis) AS startStorageType, " +
            "typeof(endedAtEpochMillis) AS endStorageType, typeof(status) AS statusStorageType, " +
            "typeof(openSessionId) AS slotStorageType FROM vehicle_recording_run WHERE sessionId = :sessionId ORDER BY rowid ASC",
    )
    fun observe(sessionId: String): Flow<List<VehicleRecordingRunRow>>

    @Query(
        "SELECT vehicle_recording_run.*, typeof(runId) AS runStorageType, typeof(sessionId) AS sessionStorageType, " +
            "typeof(processId) AS processStorageType, typeof(startedAtEpochMillis) AS startStorageType, " +
            "typeof(endedAtEpochMillis) AS endStorageType, typeof(status) AS statusStorageType, " +
            "typeof(openSessionId) AS slotStorageType FROM vehicle_recording_run WHERE sessionId = :sessionId ORDER BY rowid ASC",
    )
    suspend fun forSession(sessionId: String): List<VehicleRecordingRunRow>

    @Query(
        "SELECT vehicle_recording_run.*, typeof(runId) AS runStorageType, typeof(sessionId) AS sessionStorageType, " +
            "typeof(processId) AS processStorageType, typeof(startedAtEpochMillis) AS startStorageType, " +
            "typeof(endedAtEpochMillis) AS endStorageType, typeof(status) AS statusStorageType, " +
            "typeof(openSessionId) AS slotStorageType FROM vehicle_recording_run WHERE runId = :runId",
    )
    suspend fun find(runId: String): VehicleRecordingRunRow?

    @Query(
        "SELECT vehicle_recording_run.*, typeof(runId) AS runStorageType, typeof(sessionId) AS sessionStorageType, " +
            "typeof(processId) AS processStorageType, typeof(startedAtEpochMillis) AS startStorageType, " +
            "typeof(endedAtEpochMillis) AS endStorageType, typeof(status) AS statusStorageType, " +
            "typeof(openSessionId) AS slotStorageType FROM vehicle_recording_run ORDER BY rowid ASC",
    )
    suspend fun all(): List<VehicleRecordingRunRow>

    @Insert
    suspend fun insert(entity: VehicleRecordingRunEntity)

    @Query(
        "UPDATE vehicle_recording_run SET status = :status, endedAtEpochMillis = :endedAtEpochMillis, openSessionId = NULL " +
            "WHERE runId = :runId AND processId = :processId AND status = 'OPEN' AND openSessionId = sessionId " +
            "AND endedAtEpochMillis IS NULL",
    )
    suspend fun finish(
        runId: String,
        processId: String,
        status: String,
        endedAtEpochMillis: Long?,
    ): Int
}
