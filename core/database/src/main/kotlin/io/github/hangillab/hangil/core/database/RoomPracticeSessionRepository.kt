package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

internal class RoomPracticeSessionRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
        private val catalog: PracticeCatalog,
    ) : PracticeSessionRepository {
        override fun observeSessions() = database.practiceSessionDao().observe().map { entities -> entities.map { it.session() } }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult {
            if (startedAtEpochMillis <= 0) return SessionStartResult.InvalidTime
            return try {
                database.withTransaction {
                    requirePermission(canCommit)
                    val active = database.practiceSessionDao().active()
                    val prepared = catalog.resolve(database.preparationDao().current()?.selection()) as? Preparation.Ready
                    val result =
                        when {
                            active != null -> SessionStartResult.AlreadyActive(active.session())
                            prepared?.practice != practice -> SessionStartResult.PreparationChanged
                            else -> {
                                val entity =
                                    PracticeSessionEntity(
                                        sessionId = UUID.randomUUID().toString(),
                                        goalId = practice.goal.id,
                                        courseId = practice.course.id,
                                        courseVersion = practice.course.version,
                                        briefingVersion = practice.course.briefingVersion,
                                        taskId = practice.task.id,
                                        goalTitle = practice.goal.title,
                                        courseTitle = practice.course.title,
                                        taskTitle = practice.task.title,
                                        courseReviewStatus = practice.course.reviewStatus.name,
                                        startedAtEpochMillis = startedAtEpochMillis,
                                        endedAtEpochMillis = null,
                                        activeSlot = 1,
                                    )
                                database.practiceSessionDao().insert(entity)
                                SessionStartResult.Started(entity.session())
                            }
                        }
                    requirePermission(canCommit)
                    result
                }
            } catch (_: WriteBlocked) {
                SessionStartResult.Blocked
            }
        }

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            if (endedAtEpochMillis <= 0) return SessionFinishResult.InvalidTime
            return try {
                database.withTransaction {
                    requirePermission(canCommit)
                    val entity = database.practiceSessionDao().find(sessionId)
                    val result =
                        when {
                            entity == null -> SessionFinishResult.Missing
                            entity.endedAtEpochMillis != null -> SessionFinishResult.AlreadyFinished(entity.session())
                            endedAtEpochMillis < entity.startedAtEpochMillis -> SessionFinishResult.InvalidTime
                            else -> {
                                check(database.practiceSessionDao().finish(sessionId, endedAtEpochMillis) == 1)
                                SessionFinishResult.Finished(
                                    entity.copy(endedAtEpochMillis = endedAtEpochMillis, activeSlot = null).session(),
                                )
                            }
                        }
                    requirePermission(canCommit)
                    result
                }
            } catch (_: WriteBlocked) {
                SessionFinishResult.Blocked
            }
        }

        override suspend fun finish(
            expectedSource: PracticeSession,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            if (endedAtEpochMillis <= 0) return SessionFinishResult.InvalidTime
            if (!expectedSource.isActive) return SessionFinishResult.SourceChanged
            return try {
                database.withTransaction {
                    requireStrictPermission(canCommit)
                    val dao = database.practiceSessionDao()
                    val entity = dao.find(expectedSource.sessionId)
                    val result =
                        if (entity == null) {
                            SessionFinishResult.Missing
                        } else {
                            check(dao.countValidDeletionStorage(expectedSource.sessionId) == 1)
                            val actual = entity.session()
                            when {
                                actual.copy(endedAtEpochMillis = null) != expectedSource -> SessionFinishResult.SourceChanged
                                !actual.isActive -> SessionFinishResult.AlreadyFinished(actual)
                                endedAtEpochMillis < actual.startedAtEpochMillis -> SessionFinishResult.InvalidTime
                                else -> {
                                    check(dao.finish(expectedSource.sessionId, endedAtEpochMillis) == 1)
                                    SessionFinishResult.Finished(actual.copy(endedAtEpochMillis = endedAtEpochMillis))
                                }
                            }
                        }
                    requireStrictPermission(canCommit)
                    result
                }
            } catch (_: WriteBlocked) {
                SessionFinishResult.Blocked
            }
        }

        private suspend fun requireStrictPermission(canCommit: () -> Boolean) {
            currentCoroutineContext().ensureActive()
            if (!canCommit()) throw WriteBlocked()
            currentCoroutineContext().ensureActive()
        }

        private fun requirePermission(canCommit: () -> Boolean) {
            if (!canCommit()) throw WriteBlocked()
        }

        private class WriteBlocked : RuntimeException()
    }
