package io.github.hangillab.hangil.core.domain

/** Writes a note for the exact ended history snapshot, independently of recency. */
interface HistorySessionMemoWriter {
    /**
     * Creates the first note only while the full ended source matches, no session is active,
     * and the selected note is still absent. An existing note conflicts. The live permission
     * gate and cancellation are checked before and after the insert in the same transaction.
     * Preserve the supplied text exactly and issue a new opaque version. Reread the stored note
     * if result delivery is cancelled after COMMIT.
     */
    suspend fun create(
        expectedSource: PracticeSession,
        text: String,
        canCommit: () -> Boolean,
    ): SessionMemoWriteResult = SessionMemoWriteResult.Blocked

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
