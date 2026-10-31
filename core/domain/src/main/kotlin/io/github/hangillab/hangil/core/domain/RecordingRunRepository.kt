package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

interface RecordingRunRepository {
    /** Stored creation order, without inferring recording coverage from wall-clock ordering. */
    fun observe(sessionId: String): Flow<List<VehicleRecordingRun>>

    /**
     * Open only for the exact active source, with at most one OPEN run per session. The pure host
     * permission gate is checked before and after the write; denial or cancellation rolls it back.
     * Run identities are never reused. Invalid identities and storage errors propagate.
     */
    suspend fun open(
        source: PracticeSession,
        runId: String,
        processId: String,
        startedAtEpochMillis: Long,
        canCommit: () -> Boolean,
    ): RecordingRunOpenResult

    /**
     * Update only the exact process owner's OPEN row to STOPPED or FAILED. A null end time is
     * explicitly unknown; invalid known times, missing rows and terminal rows return false.
     * Ending or deleting a session never causes a marker to be inserted or resurrected.
     */
    suspend fun finish(
        runId: String,
        processId: String,
        status: RecordingRunStatus,
        endedAtEpochMillis: Long?,
    ): Boolean

    /** Mark older-process OPEN rows INTERRUPTED with unknown end time; never restart recording. */
    suspend fun recoverInterrupted(processId: String)
}
