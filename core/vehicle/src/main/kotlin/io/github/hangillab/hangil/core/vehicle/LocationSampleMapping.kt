package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.location.fused.model.LocationData
import io.github.hangillab.hangil.core.domain.LastKnownLocation
import io.github.hangillab.hangil.core.domain.LocationReading

internal fun LocationData?.toLocationReading(receivedAtElapsedRealtimeMillis: Long): LocationReading {
    if (this == null) return LocationReading.Empty
    return try {
        LocationReading.LastKnown(
            LastKnownLocation(
                latitude = latitude,
                longitude = longitude,
                altitudeRaw = altitude,
                headingRaw = heading,
                confidenceRaw = confidence,
                sdkTimestampMillis = timestampMillis,
                receivedAtElapsedRealtimeMillis = receivedAtElapsedRealtimeMillis,
            ),
        )
    } catch (_: IllegalArgumentException) {
        LocationReading.Error
    }
}
