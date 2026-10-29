package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
internal interface VehicleObservationDao {
    @Query(
        "SELECT vehicle_observation.*, typeof(brakeValue) AS brakeStorageType, " +
            "typeof(observedAtEpochMillis) AS observedTimeStorageType " +
            "FROM vehicle_observation WHERE sessionId = :sessionId ORDER BY id ASC",
    )
    fun observe(sessionId: String): Flow<List<VehicleObservationRow>>

    @Insert
    suspend fun insert(entity: VehicleObservationEntity): Long
}
