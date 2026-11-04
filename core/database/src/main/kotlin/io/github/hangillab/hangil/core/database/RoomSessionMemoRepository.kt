package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject

internal class RoomSessionMemoRepository
    @Inject
    constructor(
        private val database: PreparationDatabase,
    ) : SessionMemoRepository {
        override fun observe(sessionId: String) = database.sessionMemoDao().observe(sessionId).map { it?.memo() }

        override suspend fun save(
            sessionId: String,
            text: String,
            expectedVersion: String?,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult =
            mutate(sessionId, canCommit) {
                if (!SessionMemoPolicy.isValidText(text)) {
                    SessionMemoWriteResult.InvalidText
                } else {
                    val current = database.sessionMemoDao().find(sessionId)?.memo()
                    if (current?.version != expectedVersion) {
                        SessionMemoWriteResult.Conflict
                    } else {
                        val next = SessionMemoEntity(sessionId, text, nextVersion(current?.version))
                        if (current == null) {
                            database.sessionMemoDao().insert(next)
                        } else {
                            check(database.sessionMemoDao().update(sessionId, text, next.version, current.version) == 1)
                        }
                        SessionMemoWriteResult.Saved(next.memo())
                    }
                }
            }

        override suspend fun delete(
            sessionId: String,
            expectedVersion: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult =
            mutate(sessionId, canCommit) {
                val current = database.sessionMemoDao().find(sessionId)?.memo()
                if (current == null || current.version != expectedVersion) {
                    SessionMemoWriteResult.Conflict
                } else {
                    check(database.sessionMemoDao().delete(sessionId, expectedVersion) == 1)
                    SessionMemoWriteResult.Deleted
                }
            }

        private suspend fun mutate(
            sessionId: String,
            canCommit: () -> Boolean,
            action: suspend () -> SessionMemoWriteResult,
        ): SessionMemoWriteResult =
            try {
                database.withTransaction {
                    currentCoroutineContext().ensureActive()
                    requirePermission(canCommit)
                    val session = findCheckedSession(sessionId)
                    val result =
                        when {
                            session == null -> SessionMemoWriteResult.MissingSession
                            session.isActive -> SessionMemoWriteResult.SessionActive
                            database
                                .practiceSessionDao()
                                .latestFinished()
                                ?.session()
                                ?.sessionId != sessionId ->
                                SessionMemoWriteResult.SessionChanged
                            else -> action()
                        }
                    currentCoroutineContext().ensureActive()
                    requirePermission(canCommit)
                    result
                }
            } catch (_: WriteBlocked) {
                SessionMemoWriteResult.Blocked
            }

        private suspend fun findCheckedSession(sessionId: String): PracticeSession? {
            val dao = database.practiceSessionDao()
            val entity = dao.find(sessionId) ?: return null
            // Reject original storage before Room coercion can hide invalid source values.
            check(dao.countValidDeletionStorage(sessionId) == 1) { "Invalid latest memo source storage" }
            val source = entity.session()
            val selection = source.selection
            check(
                source.sessionId.isNotBlank() && selection.goalId.isNotBlank() && selection.courseId.isNotBlank() &&
                    selection.taskId.isNotBlank() && source.goalTitle.isNotBlank() && source.courseTitle.isNotBlank() &&
                    source.taskTitle.isNotBlank(),
            ) { "Invalid latest memo source snapshot" }
            return source
        }

        private fun requirePermission(canCommit: () -> Boolean) {
            if (!canCommit()) throw WriteBlocked()
        }

        private fun nextVersion(previous: String?): String {
            var version: String
            do {
                version = UUID.randomUUID().toString()
            } while (version == previous)
            return version
        }

        private class WriteBlocked : RuntimeException()
    }
