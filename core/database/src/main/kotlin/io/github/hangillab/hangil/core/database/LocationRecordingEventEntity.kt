package io.github.hangillab.hangil.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
import io.github.hangillab.hangil.core.domain.LocationUpdateErrorOrigin
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.LocationUpdateSource

@Entity(
    tableName = "location_recording_event",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = LocationRecordingRunEntity::class,
            parentColumns = ["runId", "sessionId"],
            childColumns = ["runId", "sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["runId", "sessionId"]), Index(value = ["runId", "ordinal"], unique = true)],
)
internal data class LocationRecordingEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val runId: String,
    val ordinal: Int,
    val observedAtEpochMillis: Long,
    val receivedAtElapsedRealtimeMillis: Long,
    val kind: String,
    val sampleSource: String?,
    val latitude: Double?,
    val longitude: Double?,
    val altitudeRaw: Double?,
    val headingRaw: Float?,
    val confidenceRaw: Float?,
    val sdkTimestampMillis: Long?,
    val errorOrigin: String?,
    val errorClassName: String?,
) {
    fun event(): LocationRecordingEvent {
        val payload = listOf(sampleSource, latitude, longitude, altitudeRaw, headingRaw, confidenceRaw, sdkTimestampMillis)
        if (kind == "SAMPLE") {
            check(payload.all { it != null } && sampleSource == LocationUpdateSource.SDK_UPDATE.name)
        } else {
            check(payload.all { it == null }) { "Unavailable location event contains a sample" }
        }
        check((kind == "ERROR") == (errorOrigin != null && errorClassName != null)) { "Invalid location error shape" }
        if (kind != "ERROR") check(errorOrigin == null && errorClassName == null)
        val reading =
            when (kind) {
                "SAMPLE" ->
                    LocationUpdateReading.Sample(
                        LocationUpdateSample(
                            checkNotNull(latitude),
                            checkNotNull(longitude),
                            checkNotNull(altitudeRaw),
                            checkNotNull(headingRaw),
                            checkNotNull(confidenceRaw),
                            checkNotNull(sdkTimestampMillis),
                            receivedAtElapsedRealtimeMillis,
                        ),
                    )
                "EMPTY" -> LocationUpdateReading.Empty(receivedAtElapsedRealtimeMillis)
                "PERMISSION_DENIED" -> LocationUpdateReading.PermissionDenied(receivedAtElapsedRealtimeMillis)
                "UNSUPPORTED" -> LocationUpdateReading.Unsupported(receivedAtElapsedRealtimeMillis)
                "NO_UPDATE" -> LocationUpdateReading.NoUpdate(receivedAtElapsedRealtimeMillis)
                "ERROR" ->
                    LocationUpdateReading.Error(
                        LocationUpdateErrorOrigin.valueOf(checkNotNull(errorOrigin)),
                        checkNotNull(errorClassName),
                        receivedAtElapsedRealtimeMillis,
                    )
                else -> error("Unknown location event kind")
            }
        return LocationRecordingEvent(id, sessionId, runId, ordinal, observedAtEpochMillis, reading)
    }

    companion object {
        fun from(
            sessionId: String,
            runId: String,
            ordinal: Int,
            observedAtEpochMillis: Long,
            reading: LocationUpdateReading,
        ): LocationRecordingEventEntity {
            val sample = (reading as? LocationUpdateReading.Sample)?.sample
            val failure = reading as? LocationUpdateReading.Error
            val kind =
                when (reading) {
                    is LocationUpdateReading.Sample -> "SAMPLE"
                    is LocationUpdateReading.Empty -> "EMPTY"
                    is LocationUpdateReading.PermissionDenied -> "PERMISSION_DENIED"
                    is LocationUpdateReading.Unsupported -> "UNSUPPORTED"
                    is LocationUpdateReading.NoUpdate -> "NO_UPDATE"
                    is LocationUpdateReading.Error -> "ERROR"
                }
            return LocationRecordingEventEntity(
                sessionId = sessionId,
                runId = runId,
                ordinal = ordinal,
                observedAtEpochMillis = observedAtEpochMillis,
                receivedAtElapsedRealtimeMillis = reading.receivedAtElapsedRealtimeMillis,
                kind = kind,
                sampleSource = sample?.source?.name,
                latitude = sample?.latitude,
                longitude = sample?.longitude,
                altitudeRaw = sample?.altitudeRaw,
                headingRaw = sample?.headingRaw,
                confidenceRaw = sample?.confidenceRaw,
                sdkTimestampMillis = sample?.sdkTimestampMillis,
                errorOrigin = failure?.origin?.name,
                errorClassName = failure?.exceptionClassName,
            )
        }
    }
}
