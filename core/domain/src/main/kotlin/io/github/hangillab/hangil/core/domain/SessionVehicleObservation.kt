package io.github.hangillab.hangil.core.domain

/** A stored observation linked to its session. The assigned ID preserves append order. */
data class SessionVehicleObservation(
    val id: Long,
    val sessionId: String,
    val observation: VehicleObservation,
)
