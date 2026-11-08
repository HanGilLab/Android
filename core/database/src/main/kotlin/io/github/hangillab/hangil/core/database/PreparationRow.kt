package io.github.hangillab.hangil.core.database

import androidx.room.Embedded
import io.github.hangillab.hangil.core.domain.PracticeSelection

/** The model and its original storage metadata are read in one SQLite statement. */
internal data class PreparationRow(
    @Embedded val entity: PreparationEntity,
    val goalStorageType: String,
    val courseStorageType: String,
    val taskStorageType: String,
    val plannedRoadStorageType: String,
    val courseVersionStorageType: String,
    val briefingVersionStorageType: String,
    val storedCourseVersion: Long,
    val storedBriefingVersion: Long,
) {
    fun selection(): PracticeSelection {
        check(
            goalStorageType == "text" && courseStorageType == "text" && taskStorageType == "text" &&
                courseVersionStorageType == "integer" && briefingVersionStorageType == "integer" &&
                storedCourseVersion in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                storedBriefingVersion in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() &&
                plannedRoadStorageType in listOf("null", "text"),
        ) { "Invalid stored preparation types or version range" }
        return entity.selection()
    }
}
