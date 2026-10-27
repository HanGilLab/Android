package io.github.hangillab.hangil.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PreparationEntity::class], version = 1, exportSchema = true)
internal abstract class PreparationDatabase : RoomDatabase() {
    abstract fun preparationDao(): PreparationDao
}
