package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

interface CourseCompletionRepository {
    /** Null is actual absence; malformed storage and read failures must propagate. */
    fun observe(sessionId: String): Flow<CourseCompletionDeclaration?>

    /**
     * Declare once for an exact ended source when no session is active. Rechecks the pure live
     * permission gate before and after the transaction's work; observed denial/cancellation before
     * COMMIT rolls back. A duplicate returns the original snapshot, version and time unchanged.
     * A cancelled result delivery does not prove that COMMIT failed: reread the durable declaration.
     * Does not change preparation, session data, recording, or a global course lifecycle.
     */
    suspend fun declare(
        expectedSource: PracticeSession,
        declaredAtEpochMillis: Long,
        canCommit: () -> Boolean,
    ): CourseCompletionDeclarationResult
}
