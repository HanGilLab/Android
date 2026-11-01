package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

private const val RUN_SELECT =
    "SELECT location_recording_run.*, CASE WHEN typeof(rowId) = 'integer' AND typeof(runId) = 'text' AND " +
        "typeof(sessionId) = 'text' AND typeof(processId) = 'text' AND typeof(goalId) = 'text' AND " +
        "typeof(courseId) = 'text' AND typeof(courseVersion) = 'integer' AND typeof(briefingVersion) = " +
        "'integer' AND typeof(taskId) = 'text' AND typeof(goalTitle) = 'text' AND typeof(courseTitle) = " +
        "'text' AND typeof(taskTitle) = 'text' AND typeof(courseReviewStatus) = 'text' AND " +
        "typeof(sourceStartedAtEpochMillis) = 'integer' AND typeof(startedAtEpochMillis) = 'integer' AND " +
        "typeof(startedAtElapsedRealtimeMillis) = 'integer' AND (endedAtEpochMillis IS NULL OR " +
        "typeof(endedAtEpochMillis) = 'integer') AND (endedAtElapsedRealtimeMillis IS NULL OR " +
        "typeof(endedAtElapsedRealtimeMillis) = 'integer') AND typeof(status) = 'text' AND (stopReason IS " +
        "NULL OR typeof(stopReason) = 'text') AND (openSlot IS NULL OR typeof(openSlot) = 'integer') THEN 1 " +
        "ELSE 0 END AS validStorage FROM location_recording_run"
private const val EVENT_SELECT =
    "SELECT location_recording_event.*, CASE WHEN typeof(id) = 'integer' AND typeof(sessionId) = 'text' " +
        "AND typeof(runId) = 'text' AND typeof(ordinal) = 'integer' AND typeof(observedAtEpochMillis) = " +
        "'integer' AND typeof(receivedAtElapsedRealtimeMillis) = 'integer' AND typeof(kind) = 'text' AND " +
        "(sampleSource IS NULL OR typeof(sampleSource) = 'text') AND (latitude IS NULL OR typeof(latitude) = " +
        "'real') AND (longitude IS NULL OR typeof(longitude) = 'real') AND (altitudeRaw IS NULL OR " +
        "typeof(altitudeRaw) = 'real') AND (headingRaw IS NULL OR typeof(headingRaw) = 'real') AND " +
        "(confidenceRaw IS NULL OR typeof(confidenceRaw) = 'real') AND (sdkTimestampMillis IS NULL OR " +
        "typeof(sdkTimestampMillis) = 'integer') AND (errorOrigin IS NULL OR typeof(errorOrigin) = 'text') " +
        "AND (errorClassName IS NULL OR typeof(errorClassName) = 'text') THEN 1 ELSE 0 END AS validStorage " +
        "FROM location_recording_event"

@Dao
internal interface LocationRecordingDao {
    @Query(RUN_SELECT + " WHERE sessionId = :sessionId ORDER BY rowId ASC")
    fun observeRuns(sessionId: String): Flow<List<LocationRecordingRunRow>>

    @Query(EVENT_SELECT + " WHERE sessionId = :sessionId ORDER BY id ASC")
    fun observeEvents(sessionId: String): Flow<List<LocationRecordingEventRow>>

    @Query(RUN_SELECT + " WHERE sessionId = :sessionId ORDER BY rowId ASC")
    suspend fun runs(sessionId: String): List<LocationRecordingRunRow>

    @Query(EVENT_SELECT + " WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun events(sessionId: String): List<LocationRecordingEventRow>

    @Query(RUN_SELECT + " ORDER BY rowId ASC")
    suspend fun allRuns(): List<LocationRecordingRunRow>

    @Query(RUN_SELECT + " WHERE runId = :runId")
    suspend fun findRun(runId: String): LocationRecordingRunRow?

    @Insert
    suspend fun insertRun(entity: LocationRecordingRunEntity): Long

    @Insert
    suspend fun insertEvent(entity: LocationRecordingEventEntity): Long

    @Query(
        "UPDATE location_recording_run SET status = :status, stopReason = :reason, endedAtEpochMillis = :wall, " +
            "endedAtElapsedRealtimeMillis = :elapsed, openSlot = NULL WHERE runId = :runId AND processId = :processId " +
            "AND status = 'OPEN' AND openSlot = 1 AND endedAtEpochMillis IS NULL AND endedAtElapsedRealtimeMillis IS NULL",
    )
    suspend fun end(
        runId: String,
        processId: String,
        status: String,
        reason: String,
        wall: Long?,
        elapsed: Long?,
    ): Int
}
