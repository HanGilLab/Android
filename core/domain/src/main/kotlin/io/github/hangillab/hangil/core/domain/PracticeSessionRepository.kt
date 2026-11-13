package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

interface PracticeSessionRepository {
    /** Newest created session first; ordering must not depend on a wall clock that can move backward. */
    fun observeSessions(): Flow<List<PracticeSession>>

    /** Observe the current snapshot of the requested identity, including its disappearance. */
    fun observeSession(sessionId: String): Flow<PracticeSession?> =
        observeSessions().map { sessions -> sessions.singleOrNull { it.sessionId == sessionId } }

    /** Observe the sole active session. Ambiguous ownership is a read failure. */
    fun observeActiveSession(): Flow<PracticeSession?> =
        observeSessions().map { sessions ->
            val active = sessions.filter { it.isActive }
            check(active.size <= 1) { "Invalid unfinished session ownership" }
            active.singleOrNull()
        }

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
