package io.github.hangillab.hangil.core.database

import androidx.room.Embedded
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent

internal data class LocationRecordingEventRow(
    @Embedded val entity: LocationRecordingEventEntity,
    val validStorage: Int,
) {
    fun event(): LocationRecordingEvent {
        check(validStorage == 1) { "Invalid location event physical storage" }
        return entity.event()
    }
}
