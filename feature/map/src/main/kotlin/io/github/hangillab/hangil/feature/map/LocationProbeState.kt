package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.LocationReading

sealed interface LocationProbeState {
    data object Idle : LocationProbeState

    data object AwaitingPermission : LocationProbeState

    data object Loading : LocationProbeState

    data class Finished(
        val reading: LocationReading,
    ) : LocationProbeState
}
