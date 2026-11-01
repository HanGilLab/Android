package io.github.hangillab.hangil.core.domain

/** One consistent storage snapshot; actual absence and read/decoding failure are different. */
data class LocationRecordingSnapshot(
    val runs: List<LocationRecordingRun>,
    val events: List<LocationRecordingEvent>,
)
