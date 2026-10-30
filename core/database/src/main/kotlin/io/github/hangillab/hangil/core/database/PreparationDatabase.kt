package io.github.hangillab.hangil.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [PreparationEntity::class, PracticeSessionEntity::class, VehicleObservationEntity::class, SessionMemoEntity::class],
    version = 4,
    exportSchema = true,
)
internal abstract class PreparationDatabase : RoomDatabase() {
    abstract fun preparationDao(): PreparationDao

    abstract fun practiceSessionDao(): PracticeSessionDao

    abstract fun vehicleObservationDao(): VehicleObservationDao

    abstract fun sessionMemoDao(): SessionMemoDao
}
