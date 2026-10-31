package io.github.hangillab.hangil.core.database

import androidx.room.Embedded
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun

/** Validate original physical types before Room coercion can hide malformed persisted markers. */
internal data class VehicleRecordingRunRow(
    @Embedded val entity: VehicleRecordingRunEntity,
    val runStorageType: String,
    val sessionStorageType: String,
    val processStorageType: String,
    val startStorageType: String,
    val endStorageType: String,
    val statusStorageType: String,
    val slotStorageType: String,
) {
    fun run(): VehicleRecordingRun {
        check(runStorageType == "text" && sessionStorageType == "text" && processStorageType == "text" && statusStorageType == "text") {
            "Invalid stored recording run text storage type"
        }
        check(startStorageType == "integer" && endStorageType == if (entity.endedAtEpochMillis == null) "null" else "integer") {
            "Invalid stored recording run time storage type"
        }
        check(slotStorageType == if (entity.openSessionId == null) "null" else "text") { "Invalid stored recording run slot storage type" }
        return entity.run()
    }
}
