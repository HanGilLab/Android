package io.github.hangillab.hangil.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionOrigin
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession

@Entity(
    tableName = "course_completion_declaration",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
internal data class CourseCompletionDeclarationEntity(
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
    val declaredAtEpochMillis: Long,
    val origin: String,
) {
    fun declaration() =
        CourseCompletionDeclaration(
            PracticeSession(
                sessionId,
                PracticeSelection(goalId, courseId, courseVersion, briefingVersion, taskId),
                goalTitle,
                courseTitle,
                taskTitle,
                CourseReviewStatus.valueOf(courseReviewStatus),
                startedAtEpochMillis,
                endedAtEpochMillis,
            ),
            version,
            declaredAtEpochMillis,
            CourseCompletionOrigin.valueOf(origin),
        )

    companion object {
        fun from(declaration: CourseCompletionDeclaration): CourseCompletionDeclarationEntity {
            val source = declaration.source
            val selection = source.selection
            return CourseCompletionDeclarationEntity(
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
                declaration.version,
                declaration.declaredAtEpochMillis,
                declaration.origin.name,
            )
        }
    }
}
