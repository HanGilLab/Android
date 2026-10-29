package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

/** Stores already observed SDK-derived snapshots; this contract does not start SDK collection. */
interface VehicleObservationRepository {
    /** Append order, oldest ID first, independently of observation clock changes. */
    fun observe(sessionId: String): Flow<List<SessionVehicleObservation>>

    /**
     * Rejects nonpositive observation times and appends only while the specified session exists
     * and is active. The session check and append must be atomic with session completion.
     * Positive wall-clock regressions are preserved without changing the supplied readings.
     */
    suspend fun append(
        sessionId: String,
        observation: VehicleObservation,
    ): VehicleObservationAppendResult
}
