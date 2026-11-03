package io.github.hangillab.hangil.core.domain

/** An update callback payload; SDK time base and confidence units remain unverified raw values. */
data class LocationUpdateSample(
    val latitude: Double,
    val longitude: Double,
    val altitudeRaw: Double,
    val headingRaw: Float,
    val confidenceRaw: Float,
    val sdkTimestampMillis: Long,
    val receivedAtElapsedRealtimeMillis: Long,
) {
    init {
        require(latitude.isFinite() && latitude in -90.0..90.0) { "Invalid update latitude" }
        require(longitude.isFinite() && longitude in -180.0..180.0) { "Invalid update longitude" }
        require(altitudeRaw.isFinite() && headingRaw.isFinite() && confidenceRaw.isFinite()) { "Invalid update raw fields" }
        require(receivedAtElapsedRealtimeMillis >= 0) { "Invalid update receipt time" }
    }

    val source: LocationUpdateSource get() = LocationUpdateSource.SDK_UPDATE

    override fun toString(): String = "LocationUpdateSample(source=$source, rawFields=redacted)"
}
