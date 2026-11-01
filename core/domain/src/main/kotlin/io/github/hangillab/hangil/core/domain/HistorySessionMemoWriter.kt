package io.github.hangillab.hangil.core.domain

/** Updates an existing note for the exact ended history snapshot, independently of recency. */
interface HistorySessionMemoWriter {
    /**
     * Never creates or deletes a note. The full source and nonnull original memo must still match,
     * and no session may be active. Check the live permission gate and cancellation before and
     * after the transaction's write; denial before COMMIT rolls back the update. Preserve the
     * supplied text exactly and issue a new opaque version, including for identical text.
     * Missing or changed notes conflict. Malformed stored data and SQL failures propagate.
     * A cancelled result delivery does not prove that COMMIT failed: reread the stored note.
     */
    suspend fun update(
        expectedSource: PracticeSession,
        expectedMemo: SessionMemo,
        text: String,
        canCommit: () -> Boolean,
    ): SessionMemoWriteResult
}
