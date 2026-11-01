package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

interface LocationRecordingRepository {
    /** Runs in creation order and events in append order, from one consistent transaction. */
    fun observe(sessionId: String): Flow<LocationRecordingSnapshot>

    suspend fun begin(
        expectedSource: PracticeSession,
        runId: String,
        processId: String,
        startedAtEpochMillis: Long,
        startedAtElapsedRealtimeMillis: Long,
        canBegin: () -> Boolean,
    ): LocationRecordingBeginResult

    /**
     * Checks exact source/open owner and the supplied predicate at tx entry and before commit.
     * Sample predicates freshly read permission then recheck owner/source/host; terminal metadata
     * needs the current owner/source/host but may preserve an observed permission denial.
     * Cancellation after COMMIT does not prove absence and must never cause automatic replay.
     */
    suspend fun append(
        expectedSource: PracticeSession,
        expectedRun: LocationRecordingRun,
        reading: LocationUpdateReading,
        observedAtEpochMillis: Long,
        canAppend: () -> Boolean,
    ): LocationRecordingAppendResult

    /** Exact stored owner cleanup remains allowed after host/source expiry; never inserts a run. */
    suspend fun end(
        expectedRun: LocationRecordingRun,
        status: LocationRecordingRunStatus,
        stopReason: LocationRecordingStopReason,
        endedAtEpochMillis: Long?,
        endedAtElapsedRealtimeMillis: Long?,
    ): LocationRecordingEndResult

    /** Older-process OPEN becomes INTERRUPTED/endnull; no collection or auto-resume is started. */
    suspend fun recoverInterrupted(currentProcessId: String)

    companion object {
        const val MAX_EVENTS_PER_RUN = 1024
    }
}
