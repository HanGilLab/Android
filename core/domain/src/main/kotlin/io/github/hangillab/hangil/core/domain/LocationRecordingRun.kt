package io.github.hangillab.hangil.core.domain

/** The full active source is frozen at begin; a null end means the actual ending is unknown. */
data class LocationRecordingRun(
    val runId: String,
    val processId: String,
    val source: PracticeSession,
    val startedAtEpochMillis: Long,
    val startedAtElapsedRealtimeMillis: Long,
    val endedAtEpochMillis: Long?,
    val endedAtElapsedRealtimeMillis: Long?,
    val status: LocationRecordingRunStatus,
    val stopReason: LocationRecordingStopReason?,
) {
    init {
        require(runId.isNotBlank() && processId.isNotBlank()) { "Invalid location owner" }
        require(source.isActive && source.sessionId.isNotBlank() && source.startedAtEpochMillis > 0) { "Invalid location source" }
        val selection = source.selection
        require(selection.goalId.isNotBlank() && selection.courseId.isNotBlank() && selection.taskId.isNotBlank())
        require(selection.courseVersion > 0 && selection.briefingVersion > 0)
        require(source.goalTitle.isNotBlank() && source.courseTitle.isNotBlank() && source.taskTitle.isNotBlank())
        require(startedAtEpochMillis > 0 && startedAtElapsedRealtimeMillis >= 0) { "Invalid location start" }
        require((endedAtEpochMillis == null) == (endedAtElapsedRealtimeMillis == null)) { "Partial location end" }
        require(endedAtEpochMillis == null || endedAtEpochMillis > 0)
        require(endedAtElapsedRealtimeMillis == null || endedAtElapsedRealtimeMillis >= startedAtElapsedRealtimeMillis)
        when (status) {
            LocationRecordingRunStatus.OPEN -> require(stopReason == null && endedAtEpochMillis == null)
            LocationRecordingRunStatus.INTERRUPTED ->
                require(stopReason == LocationRecordingStopReason.PROCESS_INTERRUPTED && endedAtEpochMillis == null)
            else -> require(stopReason?.status == status)
        }
    }

    override fun toString(): String = "LocationRecordingRun(owner=redacted, source=redacted, status=$status, stopReason=$stopReason)"
}
