package io.github.hangillab.hangil.core.database

import androidx.room.Embedded
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation

/** Preserve SQLite storage types alongside values that Room may coerce when reading a Long. */
internal data class VehicleObservationRow(
    @Embedded val entity: VehicleObservationEntity,
    val brakeStorageType: String,
    val observedTimeStorageType: String,
) {
    fun observation(): SessionVehicleObservation {
        check(observedTimeStorageType == "integer") { "Invalid observation time storage type" }
        check(brakeStorageType == if (entity.brakeValue == null) "null" else "integer") { "Invalid brake value storage type" }
        return entity.observation()
    }
}
