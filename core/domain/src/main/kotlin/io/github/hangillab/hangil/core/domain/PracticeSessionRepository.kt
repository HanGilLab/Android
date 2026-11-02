package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

interface PracticeSessionRepository {
    /** Newest created session first; ordering must not depend on a wall clock that can move backward. */
    fun observeSessions(): Flow<List<PracticeSession>>

    /** Recheck the live parking/UX permission inside the transaction, including after the write. */
    suspend fun start(
        practice: PreparedPractice,
        startedAtEpochMillis: Long,
        canCommit: () -> Boolean,
    ): SessionStartResult

    /** Strict finish is opt-in for adapters. Never silently delegate a full source to the ID writer. */
    suspend fun finish(
        expectedSource: PracticeSession,
        endedAtEpochMillis: Long,
        canCommit: () -> Boolean,
    ): SessionFinishResult = SessionFinishResult.Blocked

    suspend fun finish(
        sessionId: String,
        endedAtEpochMillis: Long,
        canCommit: () -> Boolean,
    ): SessionFinishResult
}
