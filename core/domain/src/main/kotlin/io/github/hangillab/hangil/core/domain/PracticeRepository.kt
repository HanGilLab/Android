package io.github.hangillab.hangil.core.domain

import kotlinx.coroutines.flow.Flow

interface PracticeRepository {
    fun observeSelection(): Flow<PracticeSelection?>

    /** Check permission inside the write transaction; false leaves the previous selection intact. */
    suspend fun saveSelection(
        selection: PracticeSelection,
        canCommit: () -> Boolean,
    ): Boolean
}
