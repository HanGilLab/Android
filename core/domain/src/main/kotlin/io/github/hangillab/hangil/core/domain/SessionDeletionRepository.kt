package io.github.hangillab.hangil.core.domain

interface SessionDeletionRepository {
    /**
     * Reads a confirmation snapshot only while permission remains allowed. Any explicitly selected
     * finished session is eligible; a newer finished or separate active session is not a conflict.
     * Invalid stored data, read errors and cancellation propagate without an empty/default target.
     */
    suspend fun prepare(
        expected: PracticeSession,
        canRead: () -> Boolean,
    ): SessionDeletionPreparation

    /**
     * Revalidates the entire target, including an absent memo version, and deletes only that finished
     * session with its observations, memo and exact recording-run snapshots atomically. Recheck permission before and after the
     * deletion and roll back when denied or cancelled. Missing is distinct from a successful delete.
     * Other sessions and the current preparation must remain unchanged. Storage errors propagate.
     */
    suspend fun delete(
        target: SessionDeletionTarget,
        canCommit: () -> Boolean,
    ): SessionDeletionResult
}
