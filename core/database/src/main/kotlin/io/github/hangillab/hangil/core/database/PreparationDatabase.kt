package io.github.hangillab.hangil.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PreparationEntity::class,
        PracticeSessionEntity::class,
        VehicleObservationEntity::class,
        SessionMemoEntity::class,
        VehicleRecordingRunEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
internal abstract class PreparationDatabase : RoomDatabase() {
    abstract fun preparationDao(): PreparationDao

    abstract fun practiceSessionDao(): PracticeSessionDao

    abstract fun vehicleObservationDao(): VehicleObservationDao

    abstract fun sessionMemoDao(): SessionMemoDao

    abstract fun vehicleRecordingRunDao(): VehicleRecordingRunDao
}
