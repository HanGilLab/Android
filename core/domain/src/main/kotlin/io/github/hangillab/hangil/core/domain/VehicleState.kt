package io.github.hangillab.hangil.core.domain

sealed interface VehicleReading<out T : Any> {
    data class Available<T : Any>(
        val value: T,
    ) : VehicleReading<T>

    data object Unknown : VehicleReading<Nothing>

    data object Unsupported : VehicleReading<Nothing>

    data object PermissionDenied : VehicleReading<Nothing>

    data object Error : VehicleReading<Nothing>
}

enum class GearPosition { PARK, REVERSE, NEUTRAL, DRIVE, OTHER }

enum class TurnSignalState { OFF, LEFT, RIGHT }

data class VehicleState(
    val connection: ConnectionState = ConnectionState.DISCONNECTED,
    val gear: VehicleReading<GearPosition> = VehicleReading.Unknown,
    val turnSignal: VehicleReading<TurnSignalState> = VehicleReading.Unknown,
    val brakePressed: VehicleReading<Boolean> = VehicleReading.Unknown,
)

fun VehicleState.toDrivingRestrictions(ux: UxRestrictionState): DrivingRestrictions =
    DrivingRestrictions(
        parkingState =
            when {
                connection != ConnectionState.CONNECTED -> ParkingState.UNKNOWN
                gear !is VehicleReading.Available -> ParkingState.UNKNOWN
                gear.value == GearPosition.PARK -> ParkingState.PARKED
                else -> ParkingState.NOT_PARKED
            },
        uxRestrictionState = ux,
    )
