package io.github.hangillab.hangil.core.domain

sealed interface VehicleObservationAppendResult {
    data class Appended(
        val id: Long,
    ) : VehicleObservationAppendResult

    data object MissingSession : VehicleObservationAppendResult

    data object FinishedSession : VehicleObservationAppendResult

    data object InvalidTime : VehicleObservationAppendResult
}
