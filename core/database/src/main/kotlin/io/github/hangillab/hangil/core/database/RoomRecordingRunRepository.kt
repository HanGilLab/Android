package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal class RoomRecordingRunRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
    ) : RecordingRunRepository {
        override fun observe(sessionId: String) = database.vehicleRecordingRunDao().observe(sessionId).map { rows -> rows.map { it.run() } }

        override suspend fun open(
            source: PracticeSession,
            runId: String,
            processId: String,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): RecordingRunOpenResult {
            require(runId.isNotBlank() && processId.isNotBlank()) { "Invalid recording run identity" }
            if (startedAtEpochMillis <= 0 || startedAtEpochMillis < source.startedAtEpochMillis) return RecordingRunOpenResult.InvalidTime
            return try {
                database.withTransaction {
                    requirePermission(canCommit)
                    val actual = checkedSession(source.sessionId)
                    val dao = database.vehicleRecordingRunDao()
                    val result =
                        when {
                            !source.isActive || actual != source -> RecordingRunOpenResult.SourceChanged
                            else -> {
                                check(dao.find(runId) == null) { "Recording run identity was already used" }
                                val previous = dao.forSession(source.sessionId).map { it.run() }
                                if (previous.any { it.status == RecordingRunStatus.OPEN }) {
                                    requirePermission(canCommit)
                                    return@withTransaction RecordingRunOpenResult.AlreadyOpen
                                }
                                // Insert ABORT intentionally rejects every reused run identity, including terminal ones.
                                val entity =
                                    VehicleRecordingRunEntity(
                                        runId,
                                        source.sessionId,
                                        processId,
                                        startedAtEpochMillis,
                                        null,
                                        RecordingRunStatus.OPEN.name,
                                        source.sessionId,
                                    )
                                dao.insert(entity)
                                RecordingRunOpenResult.Opened(entity.run())
                            }
                        }
                    requirePermission(canCommit)
                    result
                }
            } catch (_: WriteBlocked) {
                RecordingRunOpenResult.Blocked
            }
        }

        override suspend fun finish(
            runId: String,
            processId: String,
            status: RecordingRunStatus,
            endedAtEpochMillis: Long?,
        ): Boolean {
            require(runId.isNotBlank() && processId.isNotBlank()) { "Invalid recording run identity" }
            if (status != RecordingRunStatus.STOPPED && status != RecordingRunStatus.FAILED) return false
            return database.withTransaction {
                currentCoroutineContext().ensureActive()
                val dao = database.vehicleRecordingRunDao()
                val current = dao.find(runId)?.run()
                val changed =
                    if (current == null || current.processId != processId || current.status != RecordingRunStatus.OPEN ||
                        (endedAtEpochMillis != null && endedAtEpochMillis < current.startedAtEpochMillis)
                    ) {
                        false
                    } else {
                        dao.finish(runId, processId, status.name, endedAtEpochMillis) == 1
                    }
                currentCoroutineContext().ensureActive()
                changed
            }
        }

        override suspend fun recoverInterrupted(processId: String) {
            require(processId.isNotBlank()) { "Invalid recording process identity" }
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                val dao = database.vehicleRecordingRunDao()
                // Decode before changing anything; malformed storage is never repaired into normal state.
                val runs = dao.all().map { it.run() }
                for (run in runs) {
                    if (run.processId != processId && run.status == RecordingRunStatus.OPEN) {
                        check(dao.finish(run.runId, run.processId, RecordingRunStatus.INTERRUPTED.name, null) == 1) {
                            "Recording recovery update was not applied"
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
            }
        }

        private suspend fun checkedSession(sessionId: String): PracticeSession? {
            val dao = database.practiceSessionDao()
            val entity = dao.find(sessionId) ?: return null
            check(dao.countValidDeletionStorage(sessionId) == 1) { "Invalid stored recording source storage" }
            val session = entity.session()
            val selection = session.selection
            check(
                session.sessionId.isNotBlank() && selection.goalId.isNotBlank() && selection.courseId.isNotBlank() &&
                    selection.taskId.isNotBlank() && session.goalTitle.isNotBlank() && session.courseTitle.isNotBlank() &&
                    session.taskTitle.isNotBlank(),
            ) { "Invalid stored recording source snapshot" }
            return session
        }

        private suspend fun requirePermission(canCommit: () -> Boolean) {
            currentCoroutineContext().ensureActive()
            val allowed = canCommit()
            currentCoroutineContext().ensureActive()
            if (!allowed) throw WriteBlocked()
        }

        private class WriteBlocked : RuntimeException()
    }
