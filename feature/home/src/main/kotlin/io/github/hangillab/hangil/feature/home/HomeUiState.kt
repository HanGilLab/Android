package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction

data class HomeUiState(
    val vehicle: VehicleState = VehicleState(),
    val navigationConnection: ConnectionState = ConnectionState.UNAVAILABLE,
    val restrictions: DrivingRestrictions = DrivingRestrictions(),
    val preparation: PreparationLoadState = PreparationLoadState.Loading,
) {
    val allowsDetailedInteraction: Boolean get() = allowsPracticeInteraction(vehicle, restrictions)
}
