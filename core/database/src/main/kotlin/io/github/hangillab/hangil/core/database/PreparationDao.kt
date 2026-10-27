package io.github.hangillab.hangil.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
internal interface PreparationDao {
    @Query("SELECT * FROM preparation WHERE slot = 1")
    fun observe(): kotlinx.coroutines.flow.Flow<PreparationEntity?>

    @Query("SELECT * FROM preparation WHERE slot = 1")
    suspend fun current(): PreparationEntity?

    @Upsert
    suspend fun upsert(entity: PreparationEntity)
}
