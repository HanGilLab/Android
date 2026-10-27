package io.github.hangillab.hangil.core.domain

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
