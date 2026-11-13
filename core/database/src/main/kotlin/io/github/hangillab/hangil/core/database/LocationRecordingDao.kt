package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

private const val RUN_SELECT =
    "SELECT location_recording_run.*, CASE WHEN typeof(rowId) = 'integer' AND typeof(runId) = 'text' AND " +
        "typeof(sessionId) = 'text' AND typeof(processId) = 'text' AND typeof(goalId) = 'text' AND " +
        "typeof(courseId) = 'text' AND typeof(courseVersion) = 'integer' AND typeof(briefingVersion) = " +
        "'integer' AND typeof(taskId) = 'text' AND typeof(goalTitle) = 'text' AND typeof(courseTitle) = " +
        "'text' AND typeof(taskTitle) = 'text' AND typeof(courseReviewStatus) = 'text' AND " +
        "(plannedRoadJson IS NULL OR typeof(plannedRoadJson) = 'text') AND " +
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

// This is a sufficient check for the common event shapes. Unknown shapes still use the full Kotlin decoder.
private const val NEEDS_EVENT_DECODING = """
    SELECT EXISTS(
        SELECT 1
        FROM ($EVENT_SELECT) AS event
        LEFT JOIN location_recording_run AS run
          ON run.runId = event.runId AND run.sessionId = event.sessionId
        LEFT JOIN location_recording_event AS previous
          ON previous.id = (
              SELECT prior.id
              FROM location_recording_event AS prior
              WHERE prior.runId = event.runId AND prior.sessionId = event.sessionId AND prior.id < event.id
              ORDER BY prior.id DESC LIMIT 1
          )
        WHERE event.sessionId = :sessionId AND CASE WHEN
            event.validStorage = 1 AND run.runId IS NOT NULL AND event.id > 0
            AND event.ordinal BETWEEN 1 AND :maxEvents AND event.observedAtEpochMillis > 0
            AND event.receivedAtElapsedRealtimeMillis >= run.startedAtElapsedRealtimeMillis
            AND (run.endedAtElapsedRealtimeMillis IS NULL OR event.receivedAtElapsedRealtimeMillis <= run.endedAtElapsedRealtimeMillis)
            AND CASE WHEN previous.id IS NULL THEN event.ordinal = 1 ELSE
                previous.ordinal = event.ordinal - 1
                AND previous.receivedAtElapsedRealtimeMillis <= event.receivedAtElapsedRealtimeMillis
                AND previous.kind IN ('SAMPLE', 'EMPTY')
            END
            AND event.errorOrigin IS NULL AND event.errorClassName IS NULL
            AND CASE
                WHEN event.kind = 'SAMPLE' THEN
                    event.sampleSource = 'SDK_UPDATE'
                    AND event.latitude BETWEEN -90.0 AND 90.0 AND event.longitude BETWEEN -180.0 AND 180.0
                    AND event.altitudeRaw BETWEEN -1.7976931348623157e308 AND 1.7976931348623157e308
                    AND event.headingRaw BETWEEN -3.4028234663852886e38 AND 3.4028234663852886e38
                    AND event.confidenceRaw BETWEEN -3.4028234663852886e38 AND 3.4028234663852886e38
                    AND event.sdkTimestampMillis IS NOT NULL
                WHEN event.kind IN ('EMPTY', 'PERMISSION_DENIED', 'UNSUPPORTED', 'NO_UPDATE') THEN
                    event.sampleSource IS NULL AND event.latitude IS NULL AND event.longitude IS NULL
                    AND event.altitudeRaw IS NULL AND event.headingRaw IS NULL AND event.confidenceRaw IS NULL
                    AND event.sdkTimestampMillis IS NULL
                ELSE 0
            END
        THEN 0 ELSE 1 END
    )
    """

@Dao
internal interface LocationRecordingDao {
    @Query(RUN_SELECT + " WHERE sessionId = :sessionId ORDER BY rowId ASC")
    suspend fun runs(sessionId: String): List<LocationRecordingRunRow>

    @Query(EVENT_SELECT + " WHERE sessionId = :sessionId ORDER BY id ASC")
    suspend fun events(sessionId: String): List<LocationRecordingEventRow>

    @Query(
        EVENT_SELECT + " WHERE id >= :firstId AND EXISTS (" +
            "SELECT 1 FROM location_recording_run WHERE location_recording_run.sessionId = location_recording_event.sessionId" +
            ") ORDER BY id ASC LIMIT :limit",
    )
    suspend fun recoveryEvents(
        firstId: Long,
        limit: Int,
    ): List<LocationRecordingEventRow>

    @Query(NEEDS_EVENT_DECODING)
    suspend fun needsEventDecoding(
        sessionId: String,
        maxEvents: Int,
    ): Boolean

    @Query(EVENT_SELECT + " WHERE sessionId = :sessionId AND runId = :runId ORDER BY id DESC LIMIT 1")
    suspend fun lastEvent(
        sessionId: String,
        runId: String,
    ): LocationRecordingEventRow?

    @Query(RUN_SELECT + " ORDER BY rowId ASC")
    suspend fun allRuns(): List<LocationRecordingRunRow>

    @Query(RUN_SELECT + " WHERE runId = :runId")
    suspend fun findRun(runId: String): LocationRecordingRunRow?

    @Query(EVENT_SELECT + " WHERE id = :id")
    suspend fun findEvent(id: Long): LocationRecordingEventRow?

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
