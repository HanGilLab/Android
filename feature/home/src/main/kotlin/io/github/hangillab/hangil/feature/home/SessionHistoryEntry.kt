package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.PracticeSession

sealed interface SessionHistoryEntry {
    data object Explore : SessionHistoryEntry

    /** Null means the exact entry was lost. It never means explore the latest record. */
    data class FinishedReview(
        val expectedSource: PracticeSession?,
    ) : SessionHistoryEntry
}
