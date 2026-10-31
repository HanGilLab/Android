package io.github.hangillab.hangil

import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import kotlinx.coroutines.flow.Flow

/** Existing history fixtures never expand the independent recording-run disclosure. */
internal object UnexpectedHistoryRecordingRunRepository : RecordingRunRepository {
    override fun observe(sessionId: String): Flow<List<VehicleRecordingRun>> = error("Unexpected history recording-run read")

    override suspend fun open(
        source: PracticeSession,
        runId: String,
        processId: String,
        startedAtEpochMillis: Long,
        canCommit: () -> Boolean,
    ): RecordingRunOpenResult = error("History must not open a recording run")

    override suspend fun finish(
        runId: String,
        processId: String,
        status: RecordingRunStatus,
        endedAtEpochMillis: Long?,
    ): Boolean = error("History must not finish a recording run")

    override suspend fun recoverInterrupted(processId: String): Unit = error("History must not recover recording runs")
}
