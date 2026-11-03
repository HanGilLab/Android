package io.github.hangillab.hangil.core.domain

/** Assigned IDs/ordinals preserve append order even when positive wall-clock values regress. */
data class LocationRecordingEvent(
    val id: Long,
    val sessionId: String,
    val runId: String,
    val ordinal: Int,
    val observedAtEpochMillis: Long,
    val reading: LocationUpdateReading,
) {
    init {
        require(id > 0 && sessionId.isNotBlank() && runId.isNotBlank()) { "Invalid location event identity" }
        require(ordinal in 1..LocationRecordingRepository.MAX_EVENTS_PER_RUN)
        require(observedAtEpochMillis > 0)
    }

    override fun toString(): String = "LocationRecordingEvent(identity=redacted, ordinal=$ordinal, reading=$reading)"
}
