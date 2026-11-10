package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

interface SessionReviewRoadRepository {
    /** Null is actual absence. Stored snapshots remain readable independently of the current catalog. */
    fun observe(sessionId: String): Flow<SessionReviewRoad?>

    /**
     * Save for this exact ended source with no active session. The whole current choice, including
     * absence and memo text, must match. A new road and its complete catalog seed must still match
     * before and after the transaction's work. Editing the same stored road's memo needs no catalog.
     * Identical road and memo preserve the token; changed memo gets a new token without changing nodes.
     * Replacing a road with a linked sample requires explicit sample discard. The original callback stays stored.
     * Replacing a road with a memo requires explicit discard and an empty new memo; never copy notes
     * to another road. Text is preserved exactly, with empty meaning no memo.
     * A first save with expected absence may atomically link an explicitly chosen stored sample.
     * Its whole callback and immutable run owner must match before and after writing.
     * Pure live permission and cancellation checks before COMMIT roll back a rejected write.
     * If result delivery is cancelled after COMMIT, reread rather than assuming the write failed.
     */
    suspend fun save(
        expectedSource: PracticeSession,
        road: CourseRoadSnapshot,
        expectedCurrent: SessionReviewRoad?,
        memoText: String = expectedCurrent?.memoText.orEmpty(),
        discardMemo: Boolean = false,
        discardSample: Boolean = false,
        initialSample: SessionReviewSample? = null,
        canCommit: () -> Boolean,
    ): SessionReviewRoadWriteResult

    /** Link or unlink only the exact current choice. The whole callback and immutable run owner are rechecked before COMMIT. */
    suspend fun setSample(
        expectedSource: PracticeSession,
        expectedCurrent: SessionReviewRoad,
        sample: SessionReviewSample?,
        canCommit: () -> Boolean,
    ): SessionReviewRoadWriteResult

    /** Clear only this whole choice, requiring explicit discard for a memo. No current catalog is needed. */
    suspend fun clear(
        expectedSource: PracticeSession,
        expectedCurrent: SessionReviewRoad,
        discardMemo: Boolean = false,
        discardSample: Boolean = false,
        canCommit: () -> Boolean,
    ): SessionReviewRoadWriteResult
}
