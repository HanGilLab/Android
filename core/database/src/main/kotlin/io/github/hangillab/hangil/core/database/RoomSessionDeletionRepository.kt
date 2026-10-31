package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import javax.inject.Inject

internal class RoomSessionDeletionRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
    ) : SessionDeletionRepository {
        override suspend fun prepare(
            expected: PracticeSession,
            canRead: () -> Boolean,
        ): SessionDeletionPreparation =
            try {
                database.withTransaction {
                    requirePermission(canRead)
                    val actual = findCheckedSession(expected.sessionId)
                    val result =
                        when {
                            actual == null -> SessionDeletionPreparation.Missing
                            actual.isActive -> SessionDeletionPreparation.Active
                            actual != expected -> SessionDeletionPreparation.Changed
                            else -> SessionDeletionPreparation.Ready(readTarget(actual))
                        }
                    requirePermission(canRead)
                    result
                }
            } catch (_: WriteBlocked) {
                SessionDeletionPreparation.Blocked
            }

        override suspend fun delete(
            target: SessionDeletionTarget,
            canCommit: () -> Boolean,
        ): SessionDeletionResult =
            try {
                database.withTransaction {
                    requirePermission(canCommit)
                    val actual = findCheckedSession(target.session.sessionId)
                    val result =
                        when {
                            actual == null -> SessionDeletionResult.Missing
                            actual.isActive -> SessionDeletionResult.Active
                            actual != target.session -> SessionDeletionResult.Changed
                            readTarget(actual) != target -> SessionDeletionResult.Changed
                            else -> {
                                check(database.practiceSessionDao().deleteFinished(actual.sessionId) == 1)
                                SessionDeletionResult.Deleted
                            }
                        }
                    requirePermission(canCommit)
                    result
                }
            } catch (_: WriteBlocked) {
                SessionDeletionResult.Blocked
            }

        private suspend fun findCheckedSession(sessionId: String): PracticeSession? {
            val dao = database.practiceSessionDao()
            val entity = dao.find(sessionId) ?: return null
            // Room numeric/String getters may coerce malformed SQLite storage before model conversion.
            check(dao.countValidDeletionStorage(sessionId) == 1) { "Invalid stored session deletion storage" }
            val session = entity.session()
            val selection = session.selection
            // Keep the existing history contract; do not make an unreadable record a deletion target.
            check(
                session.sessionId.isNotBlank() && selection.goalId.isNotBlank() && selection.courseId.isNotBlank() &&
                    selection.taskId.isNotBlank() && selection.courseVersion > 0 && selection.briefingVersion > 0 &&
                    session.goalTitle.isNotBlank() && session.courseTitle.isNotBlank() && session.taskTitle.isNotBlank(),
            ) { "Invalid stored session deletion snapshot" }
            return session
        }

        private suspend fun readTarget(session: PracticeSession): SessionDeletionTarget {
            val memo = database.sessionMemoDao().find(session.sessionId)?.memo()
            val observationCount = database.vehicleObservationDao().count(session.sessionId)
            val runs = database.vehicleRecordingRunDao().forSession(session.sessionId).map { it.run() }
            return SessionDeletionTarget(session, observationCount, memo?.version, runs)
        }

        private suspend fun requirePermission(canProceed: () -> Boolean) {
            currentCoroutineContext().ensureActive()
            val allowed = canProceed()
            currentCoroutineContext().ensureActive()
            if (!allowed) throw WriteBlocked()
        }

        private class WriteBlocked : RuntimeException()
    }
