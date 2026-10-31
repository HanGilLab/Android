package io.github.hangillab.hangil.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun

@Entity(
    tableName = "vehicle_recording_run",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"]), Index(value = ["openSessionId"], unique = true)],
)
internal data class VehicleRecordingRunEntity(
    @PrimaryKey val runId: String,
    val sessionId: String,
    val processId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val status: String,
    // Only an OPEN row owns its session's unique nullable slot.
    val openSessionId: String?,
) {
    fun run(): VehicleRecordingRun {
        check(runId.isNotBlank() && sessionId.isNotBlank() && processId.isNotBlank()) { "Invalid stored recording run identity" }
        check(startedAtEpochMillis > 0 && (endedAtEpochMillis == null || endedAtEpochMillis >= startedAtEpochMillis)) {
            "Invalid stored recording run time"
        }
        val state = RecordingRunStatus.valueOf(status)
        check(openSessionId == if (state == RecordingRunStatus.OPEN) sessionId else null) { "Invalid stored recording run slot" }
        check((state != RecordingRunStatus.OPEN && state != RecordingRunStatus.INTERRUPTED) || endedAtEpochMillis == null) {
            "Invalid stored recording run terminal time"
        }
        return VehicleRecordingRun(runId, sessionId, processId, startedAtEpochMillis, endedAtEpochMillis, state)
    }
}
