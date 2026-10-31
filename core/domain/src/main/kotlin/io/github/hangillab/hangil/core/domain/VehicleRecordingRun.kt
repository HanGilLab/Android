package io.github.hangillab.hangil.core.domain

/** A null terminal time is explicitly unknown; process loss never receives an invented end time. */
data class VehicleRecordingRun(
    val runId: String,
    val sessionId: String,
    val processId: String,
    val startedAtEpochMillis: Long,
    val endedAtEpochMillis: Long?,
    val status: RecordingRunStatus,
)
