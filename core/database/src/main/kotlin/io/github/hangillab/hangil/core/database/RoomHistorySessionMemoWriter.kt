package io.github.hangillab.hangil.core.database

import androidx.room.withTransaction
import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.util.UUID
import javax.inject.Inject

internal class RoomHistorySessionMemoWriter
    @Inject
    constructor(
        private val database: PreparationDatabase,
    ) : HistorySessionMemoWriter {
        override suspend fun update(
            expectedSource: PracticeSession,
            expectedMemo: SessionMemo,
            text: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult =
            try {
                database.withTransaction {
                    requirePermission(canCommit)
                    val actual = findCheckedSession(expectedSource.sessionId)
                    val result =
                        when {
                            actual == null -> SessionMemoWriteResult.MissingSession
                            expectedMemo.sessionId != expectedSource.sessionId || actual != expectedSource ->
                                SessionMemoWriteResult.SessionChanged
                            actual.isActive || database.practiceSessionDao().unfinishedCount() != 0L ->
                                SessionMemoWriteResult.SessionActive
                            else -> updateExisting(expectedMemo, text)
                        }
                    requirePermission(canCommit)
                    result
                }
            } catch (_: WriteBlocked) {
                SessionMemoWriteResult.Blocked
            }

        private suspend fun findCheckedSession(sessionId: String): PracticeSession? {
            val dao = database.practiceSessionDao()
            val entity = dao.find(sessionId) ?: return null
            // Inspect original SQLite storage before Room can coerce values into the model types.
            check(dao.countValidDeletionStorage(sessionId) == 1) { "Invalid history memo source storage" }
            val source = entity.session()
            val selection = source.selection
            check(
                source.sessionId.isNotBlank() && selection.goalId.isNotBlank() && selection.courseId.isNotBlank() &&
                    selection.taskId.isNotBlank() && selection.courseVersion > 0 && selection.briefingVersion > 0 &&
                    source.goalTitle.isNotBlank() && source.courseTitle.isNotBlank() && source.taskTitle.isNotBlank(),
            ) { "Invalid history memo source snapshot" }
            return source
        }

        private suspend fun updateExisting(
            expectedMemo: SessionMemo,
            text: String,
        ): SessionMemoWriteResult {
            val dao = database.sessionMemoDao()
            val current = dao.find(expectedMemo.sessionId)?.memo()
            if (current == null || current != expectedMemo) return SessionMemoWriteResult.Conflict
            if (!SessionMemoPolicy.isValidText(text)) return SessionMemoWriteResult.InvalidText
            val next = SessionMemoEntity(current.sessionId, text, nextVersion(current.version))
            check(dao.update(current.sessionId, text, next.version, current.version) == 1)
            return SessionMemoWriteResult.Saved(next.memo())
        }

        private suspend fun requirePermission(canCommit: () -> Boolean) {
            currentCoroutineContext().ensureActive()
            val allowed = canCommit()
            currentCoroutineContext().ensureActive()
            if (!allowed) throw WriteBlocked()
        }

        private fun nextVersion(previous: String): String {
            var next: String
            do {
                next = UUID.randomUUID().toString()
            } while (next == previous)
            return next
        }

        private class WriteBlocked : RuntimeException()
    }
