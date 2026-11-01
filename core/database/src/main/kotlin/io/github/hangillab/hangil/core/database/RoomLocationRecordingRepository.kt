package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.LocationRecordingAppendResult
import io.github.hangillab.hangil.core.domain.LocationRecordingBeginResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEndResult
import io.github.hangillab.hangil.core.domain.LocationRecordingRepository
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingSnapshot
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.PracticeSession
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import javax.inject.Inject

internal class RoomLocationRecordingRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
    ) : LocationRecordingRepository {
        private val dao get() = database.locationRecordingDao()

        override fun observe(sessionId: String) =
            combine(dao.observeRuns(sessionId), dao.observeEvents(sessionId)) { _, _ -> Unit }
                .map { database.withTransaction { readSnapshot(sessionId) } }

        internal suspend fun readSnapshot(sessionId: String): LocationRecordingSnapshot {
            val runs = dao.runs(sessionId).map { it.run() }
            val events = dao.events(sessionId).map { it.event() }
            check(runs.all { it.source.sessionId == sessionId }) { "Foreign location source" }
            val byRun = runs.associateBy { it.runId }
            check(byRun.size == runs.size && runs.count { it.status == LocationRecordingRunStatus.OPEN } <= 1)
            check(events.all { it.sessionId == sessionId && it.runId in byRun }) { "Orphan location event" }
            for ((runId, rows) in events.groupBy { it.runId }) {
                val run = checkNotNull(byRun[runId])
                check(rows.size <= LocationRecordingRepository.MAX_EVENTS_PER_RUN)
                var previous = run.startedAtElapsedRealtimeMillis
                rows.forEachIndexed { index, event ->
                    check(event.ordinal == index + 1) { "Invalid location append order" }
                    check(event.reading.receivedAtElapsedRealtimeMillis >= previous) { "Location receipt clock regressed" }
                    check(index == rows.lastIndex || !event.reading.isTerminal()) { "Event follows terminal location metadata" }
                    previous = event.reading.receivedAtElapsedRealtimeMillis
                }
                val endedElapsed = run.endedAtElapsedRealtimeMillis
                check(endedElapsed == null || endedElapsed >= previous)
            }
            return LocationRecordingSnapshot(runs, events)
        }

        override suspend fun begin(
            expectedSource: PracticeSession,
            runId: String,
            processId: String,
            startedAtEpochMillis: Long,
            startedAtElapsedRealtimeMillis: Long,
            canBegin: () -> Boolean,
        ): LocationRecordingBeginResult {
            require(runId.isNotBlank() && processId.isNotBlank()) { "Invalid location owner" }
            if (startedAtEpochMillis <= 0 || startedAtElapsedRealtimeMillis < 0) return LocationRecordingBeginResult.InvalidTime
            return try {
                database.withTransaction {
                    requirePermission(canBegin)
                    val actual = checkedSession(expectedSource.sessionId)
                    val result =
                        when {
                            actual == null -> LocationRecordingBeginResult.MissingSession
                            !expectedSource.isActive -> LocationRecordingBeginResult.SourceChanged
                            actual.sameEndedSource(expectedSource) -> LocationRecordingBeginResult.FinishedSession
                            actual != expectedSource -> LocationRecordingBeginResult.SourceChanged
                            else -> {
                                check(database.practiceSessionDao().unfinishedCount() == 1L) { "Invalid active location source count" }
                                val existing = dao.allRuns().map { it.run() }
                                check(existing.none { it.runId == runId }) { "Location run identity was already used" }
                                if (existing.any { it.status == LocationRecordingRunStatus.OPEN }) {
                                    LocationRecordingBeginResult.AlreadyOpen
                                } else {
                                    val run =
                                        LocationRecordingRun(
                                            runId,
                                            processId,
                                            expectedSource,
                                            startedAtEpochMillis,
                                            startedAtElapsedRealtimeMillis,
                                            null,
                                            null,
                                            LocationRecordingRunStatus.OPEN,
                                            null,
                                        )
                                    dao.insertRun(LocationRecordingRunEntity.from(run))
                                    LocationRecordingBeginResult.Begun(run)
                                }
                            }
                        }
                    requirePermission(canBegin)
                    result
                }
            } catch (_: WriteBlocked) {
                LocationRecordingBeginResult.Blocked
            }
        }

        override suspend fun append(
            expectedSource: PracticeSession,
            expectedRun: LocationRecordingRun,
            reading: LocationUpdateReading,
            observedAtEpochMillis: Long,
            canAppend: () -> Boolean,
        ): LocationRecordingAppendResult {
            if (observedAtEpochMillis <= 0) return LocationRecordingAppendResult.InvalidTime
            return try {
                database.withTransaction {
                    requirePermission(canAppend)
                    val actual = checkedSession(expectedSource.sessionId)
                    val stored = dao.findRun(expectedRun.runId)?.run()
                    val result =
                        when {
                            actual == null -> LocationRecordingAppendResult.MissingSession
                            expectedSource != expectedRun.source || !expectedSource.isActive -> LocationRecordingAppendResult.SourceChanged
                            actual.sameEndedSource(expectedSource) -> LocationRecordingAppendResult.FinishedSession
                            actual != expectedSource -> LocationRecordingAppendResult.SourceChanged
                            stored == null -> LocationRecordingAppendResult.MissingRun
                            !sameOwner(stored, expectedRun) -> LocationRecordingAppendResult.OwnerChanged
                            stored.status != LocationRecordingRunStatus.OPEN -> LocationRecordingAppendResult.RunClosed
                            else -> {
                                check(database.practiceSessionDao().unfinishedCount() == 1L) { "Invalid active location source count" }
                                val rows = readSnapshot(expectedSource.sessionId).events.filter { it.runId == stored.runId }
                                val previous =
                                    rows.lastOrNull()?.reading?.receivedAtElapsedRealtimeMillis ?: stored.startedAtElapsedRealtimeMillis
                                when {
                                    reading.receivedAtElapsedRealtimeMillis < previous -> LocationRecordingAppendResult.InvalidTime
                                    rows.lastOrNull()?.reading?.isTerminal() == true -> LocationRecordingAppendResult.RunClosed
                                    rows.size >= LocationRecordingRepository.MAX_EVENTS_PER_RUN ->
                                        LocationRecordingAppendResult.LimitReached
                                    else -> {
                                        val entity =
                                            LocationRecordingEventEntity.from(
                                                expectedSource.sessionId,
                                                stored.runId,
                                                rows.size + 1,
                                                observedAtEpochMillis,
                                                reading,
                                            )
                                        val id = dao.insertEvent(entity)
                                        LocationRecordingAppendResult.Appended(
                                            entity.copy(id = id).event(),
                                            rows.size + 1 == LocationRecordingRepository.MAX_EVENTS_PER_RUN,
                                        )
                                    }
                                }
                            }
                        }
                    requirePermission(canAppend)
                    result
                }
            } catch (_: WriteBlocked) {
                LocationRecordingAppendResult.Blocked
            }
        }

        override suspend fun end(
            expectedRun: LocationRecordingRun,
            status: LocationRecordingRunStatus,
            stopReason: LocationRecordingStopReason,
            endedAtEpochMillis: Long?,
            endedAtElapsedRealtimeMillis: Long?,
        ): LocationRecordingEndResult {
            if (status !in listOf(LocationRecordingRunStatus.STOPPED, LocationRecordingRunStatus.FAILED) || stopReason.status != status) {
                return LocationRecordingEndResult.InvalidTermination
            }
            if ((endedAtEpochMillis == null) != (endedAtElapsedRealtimeMillis == null) ||
                (endedAtEpochMillis != null && endedAtEpochMillis <= 0) ||
                (endedAtElapsedRealtimeMillis != null && endedAtElapsedRealtimeMillis < expectedRun.startedAtElapsedRealtimeMillis)
            ) {
                return LocationRecordingEndResult.InvalidTime
            }
            return database.withTransaction {
                currentCoroutineContext().ensureActive()
                val stored = dao.findRun(expectedRun.runId)?.run()
                val result =
                    when {
                        stored == null -> LocationRecordingEndResult.MissingRun
                        !sameEndOwner(stored, expectedRun) -> LocationRecordingEndResult.OwnerChanged
                        stored.status != LocationRecordingRunStatus.OPEN -> LocationRecordingEndResult.AlreadyEnded(stored)
                        else -> {
                            val latest = readSnapshot(stored.source.sessionId).events.lastOrNull { it.runId == stored.runId }
                            if (endedAtElapsedRealtimeMillis != null && latest != null &&
                                endedAtElapsedRealtimeMillis < latest.reading.receivedAtElapsedRealtimeMillis
                            ) {
                                LocationRecordingEndResult.InvalidTime
                            } else {
                                check(
                                    dao.end(
                                        stored.runId,
                                        stored.processId,
                                        status.name,
                                        stopReason.name,
                                        endedAtEpochMillis,
                                        endedAtElapsedRealtimeMillis,
                                    ) == 1,
                                )
                                LocationRecordingEndResult.Ended(
                                    stored.copy(
                                        status = status,
                                        stopReason = stopReason,
                                        endedAtEpochMillis = endedAtEpochMillis,
                                        endedAtElapsedRealtimeMillis = endedAtElapsedRealtimeMillis,
                                    ),
                                )
                            }
                        }
                    }
                currentCoroutineContext().ensureActive()
                result
            }
        }

        override suspend fun recoverInterrupted(currentProcessId: String) {
            require(currentProcessId.isNotBlank())
            database.withTransaction {
                currentCoroutineContext().ensureActive()
                val runs = dao.allRuns().map { it.run() }
                check(runs.count { it.status == LocationRecordingRunStatus.OPEN } <= 1) { "Multiple location owners" }
                runs.map { it.source.sessionId }.distinct().forEach { readSnapshot(it) }
                for (run in runs) {
                    if (run.status == LocationRecordingRunStatus.OPEN && run.processId != currentProcessId) {
                        check(
                            dao.end(
                                run.runId,
                                run.processId,
                                LocationRecordingRunStatus.INTERRUPTED.name,
                                LocationRecordingStopReason.PROCESS_INTERRUPTED.name,
                                null,
                                null,
                            ) == 1,
                        )
                    }
                }
                currentCoroutineContext().ensureActive()
            }
        }

        private suspend fun checkedSession(sessionId: String): PracticeSession? {
            val sessionDao = database.practiceSessionDao()
            val entity = sessionDao.find(sessionId) ?: return null
            check(sessionDao.countValidDeletionStorage(sessionId) == 1) { "Invalid location source storage" }
            val source = entity.session()
            val selection = source.selection
            check(
                source.sessionId.isNotBlank() && source.startedAtEpochMillis > 0 && selection.goalId.isNotBlank() &&
                    selection.courseId.isNotBlank() && selection.taskId.isNotBlank() && source.goalTitle.isNotBlank() &&
                    source.courseTitle.isNotBlank() && source.taskTitle.isNotBlank(),
            ) { "Invalid location source" }
            return source
        }

        private fun PracticeSession.sameEndedSource(expected: PracticeSession): Boolean =
            !isActive && expected.isActive && copy(endedAtEpochMillis = null) == expected

        private fun sameOwner(
            actual: LocationRecordingRun,
            expected: LocationRecordingRun,
        ): Boolean =
            expected.status == LocationRecordingRunStatus.OPEN && actual.copy(
                status = LocationRecordingRunStatus.OPEN,
                stopReason = null,
                endedAtEpochMillis = null,
                endedAtElapsedRealtimeMillis = null,
            ) == expected

        private fun sameEndOwner(
            actual: LocationRecordingRun,
            expected: LocationRecordingRun,
        ): Boolean =
            actual.runId == expected.runId && actual.processId == expected.processId && actual.source == expected.source &&
                actual.startedAtEpochMillis == expected.startedAtEpochMillis &&
                actual.startedAtElapsedRealtimeMillis == expected.startedAtElapsedRealtimeMillis

        private fun LocationUpdateReading.isTerminal(): Boolean =
            this !is LocationUpdateReading.Sample && this !is LocationUpdateReading.Empty

        private suspend fun requirePermission(canProceed: () -> Boolean) {
            currentCoroutineContext().ensureActive()
            val allowed = canProceed()
            currentCoroutineContext().ensureActive()
            if (!allowed) throw WriteBlocked()
        }

        private class WriteBlocked : RuntimeException()
    }
