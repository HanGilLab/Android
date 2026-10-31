package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction

data class MapUiState(
    val vehicle: VehicleState = VehicleState(),
    val restrictions: DrivingRestrictions = DrivingRestrictions(),
    val attempt: Int = 0,
    val load: MapLoadState = MapLoadState.LOADING,
    val preparation: MapPreparationState = MapPreparationState.Loading,
    val rendererToken: Long = 0,
    val pointSelection: MapPointSelection? = null,
) {
    val allowsDetailedInteraction: Boolean get() = allowsPracticeInteraction(vehicle, restrictions)
}
