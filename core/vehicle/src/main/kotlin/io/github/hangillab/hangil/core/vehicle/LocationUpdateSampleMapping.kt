package io.github.hangillab.hangil.core.vehicle

import ai.pleos.playground.location.fused.model.LocationData
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample

/** A callback receipt is neither a current-position guarantee nor the SDK event's time base. */
internal fun LocationData?.toLocationUpdateReading(receivedAtElapsedRealtimeMillis: Long): LocationUpdateReading {
    if (this == null) return LocationUpdateReading.Empty(receivedAtElapsedRealtimeMillis)
    return LocationUpdateReading.Sample(
        LocationUpdateSample(
            latitude = latitude,
            longitude = longitude,
            altitudeRaw = altitude,
            headingRaw = heading,
            confidenceRaw = confidence,
            sdkTimestampMillis = timestampMillis,
            receivedAtElapsedRealtimeMillis = receivedAtElapsedRealtimeMillis,
        ),
    )
}
