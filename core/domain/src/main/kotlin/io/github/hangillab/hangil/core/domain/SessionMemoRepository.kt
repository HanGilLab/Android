package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

interface SessionMemoRepository {
    /** Null means an absent note. Invalid stored data and read failures must not become null. */
    fun observe(sessionId: String): Flow<SessionMemo?>

    /**
     * Writes only for the actual latest finished session. Null expectedVersion creates a new note;
     * a nonnull token must match the current note. Recheck live parking/UX permission before and
     * after the write in the same transaction, rolling back when permission changes.
     * Text is never trimmed, truncated or replaced with a default.
     */
    suspend fun save(
        sessionId: String,
        text: String,
        expectedVersion: String?,
        canCommit: () -> Boolean,
    ): SessionMemoWriteResult

    /** Explicit deletion requires the token that was read; missing or changed notes conflict. */
    suspend fun delete(
        sessionId: String,
        expectedVersion: String,
        canCommit: () -> Boolean,
    ): SessionMemoWriteResult
}
