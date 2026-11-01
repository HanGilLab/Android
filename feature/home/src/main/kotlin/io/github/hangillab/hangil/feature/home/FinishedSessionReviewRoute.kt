package io.github.hangillab.hangil.feature.home

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import io.github.hangillab.hangil.core.domain.PracticeSession

/** Explicit exact entry. Losing its source cannot turn it into the general history route. */
@Composable
fun FinishedSessionReviewRoute(
    expectedSource: PracticeSession?,
    onClose: () -> Unit,
    viewModel: SessionHistoryViewModel = hiltViewModel(),
) {
    SessionHistoryEntryContent(SessionHistoryEntry.FinishedReview(expectedSource), onClose, viewModel)
}
