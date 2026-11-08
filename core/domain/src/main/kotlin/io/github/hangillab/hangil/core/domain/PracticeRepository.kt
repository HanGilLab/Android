package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

interface PracticeRepository {
    fun observeSelection(): Flow<PracticeSelection?>

    /** Replace only the exact observed preparation. A task-only edit retains its copied target; a different course context clears it. */
    suspend fun replaceSelection(
        expectedCurrent: PracticeSelection?,
        selection: PracticeSelection,
        canCommit: () -> Boolean,
    ): Boolean

    /** Check permission inside the write transaction; this legacy writer cannot create or replace a copied target. */
    suspend fun saveSelection(
        selection: PracticeSelection,
        canCommit: () -> Boolean,
    ): Boolean
}
