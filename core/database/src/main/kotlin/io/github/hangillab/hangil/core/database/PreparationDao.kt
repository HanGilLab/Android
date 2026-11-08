package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface PreparationDao {
    @Query("SELECT * FROM preparation WHERE slot = 1")
    suspend fun current(): PreparationEntity?

    @Query(
        "SELECT preparation.*, typeof(goalId) AS goalStorageType, typeof(courseId) AS courseStorageType, " +
            "typeof(taskId) AS taskStorageType, typeof(plannedRoadJson) AS plannedRoadStorageType, " +
            "typeof(courseVersion) AS courseVersionStorageType, " +
            "typeof(briefingVersion) AS briefingVersionStorageType, courseVersion AS storedCourseVersion, " +
            "briefingVersion AS storedBriefingVersion FROM preparation WHERE slot = 1",
    )
    fun observeChecked(): kotlinx.coroutines.flow.Flow<PreparationRow?>

    @Query(
        "SELECT preparation.*, typeof(goalId) AS goalStorageType, typeof(courseId) AS courseStorageType, " +
            "typeof(taskId) AS taskStorageType, typeof(plannedRoadJson) AS plannedRoadStorageType, " +
            "typeof(courseVersion) AS courseVersionStorageType, " +
            "typeof(briefingVersion) AS briefingVersionStorageType, courseVersion AS storedCourseVersion, " +
            "briefingVersion AS storedBriefingVersion FROM preparation WHERE slot = 1",
    )
    suspend fun currentChecked(): PreparationRow?

    @Upsert
    suspend fun upsert(entity: PreparationEntity)
}
