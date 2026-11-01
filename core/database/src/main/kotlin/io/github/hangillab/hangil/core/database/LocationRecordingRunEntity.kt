package io.github.hangillab.hangil.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession

@Entity(
    tableName = "location_recording_run",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(
            value = ["sessionId"],
        ), Index(
            value = ["runId"],
            unique = true,
        ), Index(value = ["runId", "sessionId"], unique = true), Index(value = ["openSlot"], unique = true),
    ],
)
internal data class LocationRecordingRunEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val runId: String,
    val sessionId: String,
    val processId: String,
    val goalId: String,
    val courseId: String,
    val courseVersion: Int,
    val briefingVersion: Int,
    val taskId: String,
    val goalTitle: String,
    val courseTitle: String,
    val taskTitle: String,
    val courseReviewStatus: String,
    val sourceStartedAtEpochMillis: Long,
    val startedAtEpochMillis: Long,
    val startedAtElapsedRealtimeMillis: Long,
    val endedAtEpochMillis: Long?,
    val endedAtElapsedRealtimeMillis: Long?,
    val status: String,
    val stopReason: String?,
    val openSlot: Long?,
) {
    fun run(): LocationRecordingRun {
        check(rowId > 0) { "Invalid location run row" }
        val state = LocationRecordingRunStatus.valueOf(status)
        check(openSlot == if (state == LocationRecordingRunStatus.OPEN) 1L else null) { "Invalid location open slot" }
        return LocationRecordingRun(
            runId,
            processId,
            PracticeSession(
                sessionId,
                PracticeSelection(goalId, courseId, courseVersion, briefingVersion, taskId),
                goalTitle,
                courseTitle,
                taskTitle,
                CourseReviewStatus.valueOf(courseReviewStatus),
                sourceStartedAtEpochMillis,
                null,
            ),
            startedAtEpochMillis,
            startedAtElapsedRealtimeMillis,
            endedAtEpochMillis,
            endedAtElapsedRealtimeMillis,
            state,
            stopReason?.let(LocationRecordingStopReason::valueOf),
        )
    }

    companion object {
        fun from(run: LocationRecordingRun): LocationRecordingRunEntity {
            val source = run.source
            val selection = source.selection
            return LocationRecordingRunEntity(
                runId = run.runId,
                sessionId = source.sessionId,
                processId = run.processId,
                goalId = selection.goalId,
                courseId = selection.courseId,
                courseVersion = selection.courseVersion,
                briefingVersion = selection.briefingVersion,
                taskId = selection.taskId,
                goalTitle = source.goalTitle,
                courseTitle = source.courseTitle,
                taskTitle = source.taskTitle,
                courseReviewStatus = source.courseReviewStatus.name,
                sourceStartedAtEpochMillis = source.startedAtEpochMillis,
                startedAtEpochMillis = run.startedAtEpochMillis,
                startedAtElapsedRealtimeMillis = run.startedAtElapsedRealtimeMillis,
                endedAtEpochMillis = run.endedAtEpochMillis,
                endedAtElapsedRealtimeMillis = run.endedAtElapsedRealtimeMillis,
                status = run.status.name,
                stopReason = run.stopReason?.name,
                openSlot = if (run.status == LocationRecordingRunStatus.OPEN) 1L else null,
            )
        }
    }
}
