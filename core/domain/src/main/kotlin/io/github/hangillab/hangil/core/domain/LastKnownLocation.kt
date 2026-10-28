package io.github.hangillab.hangil.core.domain

/** SDK timestamp time base and confidence units are unverified and must remain raw. */
data class LastKnownLocation(
    val latitude: Double,
    val longitude: Double,
    val altitudeRaw: Double,
    val headingRaw: Float,
    val confidenceRaw: Float,
    val sdkTimestampMillis: Long,
    val receivedAtElapsedRealtimeMillis: Long,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid location latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid location longitude" }
        require(altitudeRaw.isFinite() && headingRaw.isFinite() && confidenceRaw.isFinite()) { "Invalid location fields" }
        require(receivedAtElapsedRealtimeMillis >= 0) { "Invalid location receipt time" }
    }

    val source: LocationSource get() = LocationSource.VEHICLE_SDK_LAST_KNOWN

    override fun toString(): String = "LastKnownLocation(source=$source, rawFields=redacted)"
}
