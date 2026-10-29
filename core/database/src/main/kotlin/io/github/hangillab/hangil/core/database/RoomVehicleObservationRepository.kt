package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal class RoomVehicleObservationRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
    ) : VehicleObservationRepository {
        override fun observe(sessionId: String) =
            database.vehicleObservationDao().observe(sessionId).map { rows -> rows.map { it.observation() } }

        override suspend fun append(
            sessionId: String,
            observation: VehicleObservation,
        ): VehicleObservationAppendResult {
            if (observation.observedAtEpochMillis <= 0) return VehicleObservationAppendResult.InvalidTime
            return database.withTransaction {
                val session = database.practiceSessionDao().find(sessionId)?.session()
                when {
                    session == null -> VehicleObservationAppendResult.MissingSession
                    !session.isActive -> VehicleObservationAppendResult.FinishedSession
                    else -> {
                        currentCoroutineContext().ensureActive()
                        val id = database.vehicleObservationDao().insert(VehicleObservationEntity.from(sessionId, observation))
                        currentCoroutineContext().ensureActive()
                        VehicleObservationAppendResult.Appended(id)
                    }
                }
            }
        }
    }
