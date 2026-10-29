package io.github.hangillab.hangil.core.database

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState

@Entity(
    tableName = "vehicle_observation",
    foreignKeys = [
        ForeignKey(
            entity = PracticeSessionEntity::class,
            parentColumns = ["sessionId"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["sessionId"])],
)
internal data class VehicleObservationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val observedAtEpochMillis: Long,
    val source: String,
    val connection: String,
    val gearStatus: String,
    val gearValue: String?,
    val turnStatus: String,
    val turnValue: String?,
    val brakeStatus: String,
    // Retain the raw INTEGER so corrupt values cannot silently become true in a Room converter.
    val brakeValue: Long?,
) {
    fun observation(): SessionVehicleObservation {
        check(id > 0 && observedAtEpochMillis > 0) { "Invalid stored vehicle observation identity or time" }
        check(source == VehicleObservationSource.SDK_STATE_OBSERVATION.name) { "Unknown vehicle observation source" }
        val brake =
            brakeValue?.let {
                when (it) {
                    0L -> false
                    1L -> true
                    else -> error("Invalid stored brake value")
                }
            }
        return SessionVehicleObservation(
            id = id,
            sessionId = sessionId,
            observation =
                VehicleObservation(
                    observedAtEpochMillis = observedAtEpochMillis,
                    state =
                        VehicleState(
                            connection = ConnectionState.valueOf(connection),
                            gear = reading(gearStatus, gearValue?.let(GearPosition::valueOf)),
                            turnSignal = reading(turnStatus, turnValue?.let(TurnSignalState::valueOf)),
                            brakePressed = reading(brakeStatus, brake),
                        ),
                ),
        )
    }

    companion object {
        fun from(
            sessionId: String,
            observation: VehicleObservation,
        ) = VehicleObservationEntity(
            sessionId = sessionId,
            observedAtEpochMillis = observation.observedAtEpochMillis,
            source = observation.source.name,
            connection = observation.state.connection.name,
            gearStatus = observation.state.gear.status(),
            gearValue = (observation.state.gear as? VehicleReading.Available<GearPosition>)?.value?.name,
            turnStatus = observation.state.turnSignal.status(),
            turnValue = (observation.state.turnSignal as? VehicleReading.Available<TurnSignalState>)?.value?.name,
            brakeStatus = observation.state.brakePressed.status(),
            brakeValue = (observation.state.brakePressed as? VehicleReading.Available<Boolean>)?.value?.let { if (it) 1L else 0L },
        )
    }
}

private fun VehicleReading<*>.status(): String =
    when (this) {
        is VehicleReading.Available -> "AVAILABLE"
        VehicleReading.Unknown -> "UNKNOWN"
        VehicleReading.Unsupported -> "UNSUPPORTED"
        VehicleReading.PermissionDenied -> "PERMISSION_DENIED"
        VehicleReading.Error -> "ERROR"
    }

private fun <T : Any> reading(
    status: String,
    value: T?,
): VehicleReading<T> {
    if (status == "AVAILABLE") return VehicleReading.Available(checkNotNull(value) { "Available reading is missing its value" })
    check(value == null) { "Unavailable reading unexpectedly contains a value" }
    return when (status) {
        "UNKNOWN" -> VehicleReading.Unknown
        "UNSUPPORTED" -> VehicleReading.Unsupported
        "PERMISSION_DENIED" -> VehicleReading.PermissionDenied
        "ERROR" -> VehicleReading.Error
        else -> error("Unknown stored vehicle reading status")
    }
}
