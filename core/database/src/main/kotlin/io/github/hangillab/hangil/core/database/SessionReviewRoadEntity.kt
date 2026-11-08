package io.github.hangillab.hangil.core.database

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionReviewRoad

@Entity(
    tableName = "session_review_road",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class SessionReviewRoadEntity(
    @PrimaryKey val sessionId: String,
    val goalId: String,
    val courseId: String,
    val courseVersion: Int,
    val briefingVersion: Int,
    val taskId: String,
    val goalTitle: String,
    val courseTitle: String,
    val taskTitle: String,
    val courseReviewStatus: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long,
    val version: String,
    val origin: String,
    val roadId: String,
    val roadTitle: String,
    val highway: String,
    val osmWayId: Long,
    val osmWayVersion: Int,
    val capturedAtEpochMillis: Long,
    val nodeCount: Int,
    @ColumnInfo(defaultValue = "''") val memoText: String = "",
    @ColumnInfo(defaultValue = "NULL") val plannedRoadJson: String? = null,
    @ColumnInfo(defaultValue = "NULL") val sampleAnchorJson: String? = null,
) {
    fun source() =
        PracticeSession(
            sessionId,
            PracticeSelection(goalId, courseId, courseVersion, briefingVersion, taskId, PlannedPracticeRoadJson.decode(plannedRoadJson)),
            goalTitle,
            courseTitle,
            taskTitle,
            CourseReviewStatus.valueOf(courseReviewStatus),
            startedAtEpochMillis,
            endedAtEpochMillis,
        )

    companion object {
        fun from(review: SessionReviewRoad): SessionReviewRoadEntity {
            val source = review.source
            val selection = source.selection
            val road = review.road
            return SessionReviewRoadEntity(
                source.sessionId,
                selection.goalId,
                selection.courseId,
                selection.courseVersion,
                selection.briefingVersion,
                selection.taskId,
                source.goalTitle,
                source.courseTitle,
                source.taskTitle,
                source.courseReviewStatus.name,
                source.startedAtEpochMillis,
                checkNotNull(source.endedAtEpochMillis),
                review.version,
                review.origin,
                road.id,
                road.title,
                road.highway,
                road.osmWayId,
                road.osmWayVersion,
                road.capturedAtEpochMillis,
                road.nodes.size,
                review.memoText,
                PlannedPracticeRoadJson.encode(selection.plannedRoad),
                SessionReviewSampleJson.encode(review.sampleAnchor),
            )
        }
    }
}
