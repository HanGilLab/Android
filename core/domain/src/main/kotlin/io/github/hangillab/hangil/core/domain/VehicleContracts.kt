package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.StateFlow

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    UNAVAILABLE,
    PERMISSION_DENIED,
    ERROR,
}

enum class ParkingState {
    UNKNOWN,
    PARKED,
    NOT_PARKED,
}

enum class UxRestrictionState {
    UNKNOWN,
    RESTRICTED,
    UNRESTRICTED,
}

data class DrivingRestrictions(
    val parkingState: ParkingState = ParkingState.UNKNOWN,
    val uxRestrictionState: UxRestrictionState = UxRestrictionState.UNKNOWN,
) {
    val allowsDetailedInteraction: Boolean
        get() = parkingState == ParkingState.PARKED && uxRestrictionState == UxRestrictionState.UNRESTRICTED
}

interface VehicleGateway {
    val state: StateFlow<VehicleState>
}

/** SDK의 목적지 요청 API는 실제 연동 단계에서 추가한다. 현재는 연결 가능 여부만 제공한다. */
interface NavigationGateway {
    val connection: StateFlow<ConnectionState>
}

interface DrivingRestrictionsProvider {
    val restrictions: StateFlow<DrivingRestrictions>
}
