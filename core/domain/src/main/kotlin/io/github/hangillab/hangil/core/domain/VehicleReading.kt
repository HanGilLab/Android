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
