package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

interface SessionReviewRoadRepository {
    /** Null is actual absence. Stored snapshots remain readable independently of the current catalog. */
    fun observe(sessionId: String): Flow<SessionReviewRoad?>

    /**
     * Save for this exact ended source with no active session. The whole current choice, including
     * absence, must match. The candidate and its complete catalog seed must still match before and
     * after the transaction's work. Same-road saves with a matching current choice preserve its token.
     * Pure live permission and cancellation checks before COMMIT roll back a rejected write.
     * If result delivery is cancelled after COMMIT, reread rather than assuming the write failed.
     */
    suspend fun save(
        expectedSource: PracticeSession,
        road: CourseRoadSnapshot,
        expectedCurrent: SessionReviewRoad?,
        canCommit: () -> Boolean,
    ): SessionReviewRoadWriteResult

    /** Clear only this whole current choice. Never require its old road to remain in the catalog. */
    suspend fun clear(
        expectedSource: PracticeSession,
        expectedCurrent: SessionReviewRoad,
        canCommit: () -> Boolean,
    ): SessionReviewRoadWriteResult
}
