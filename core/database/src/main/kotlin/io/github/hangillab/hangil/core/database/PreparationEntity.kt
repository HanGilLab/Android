package io.github.hangillab.hangil.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.github.hangillab.hangil.core.domain.PracticeSelection

@Entity(tableName = "preparation")
internal data class PreparationEntity(
    @PrimaryKey val slot: Int = 1,
    val goalId: String,
    val courseId: String,
    val courseVersion: Int,
    val briefingVersion: Int,
    val taskId: String,
) {
    fun selection() = PracticeSelection(goalId, courseId, courseVersion, briefingVersion, taskId)
}
