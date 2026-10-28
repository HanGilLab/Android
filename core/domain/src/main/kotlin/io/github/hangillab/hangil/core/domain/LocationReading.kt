package io.github.hangillab.hangil.core.domain

sealed interface LocationReading {
    data class LastKnown(
        val sample: LastKnownLocation,
    ) : LocationReading

    data object Empty : LocationReading

    data object Unsupported : LocationReading

    data object PermissionDenied : LocationReading

    data object TimedOut : LocationReading

    data object Error : LocationReading
}
