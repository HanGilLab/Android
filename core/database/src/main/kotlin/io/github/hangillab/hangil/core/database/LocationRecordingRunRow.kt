package io.github.hangillab.hangil.core.database

import androidx.room.Embedded
import io.github.hangillab.hangil.core.domain.LocationRecordingRun

internal data class LocationRecordingRunRow(
    @Embedded val entity: LocationRecordingRunEntity,
    val validStorage: Int,
) {
    fun run(): LocationRecordingRun {
        check(validStorage == 1) { "Invalid location run physical storage" }
        return entity.run()
    }
}
