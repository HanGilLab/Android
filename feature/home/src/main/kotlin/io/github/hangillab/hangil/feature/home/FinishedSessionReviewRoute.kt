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
    onOpenLocations: (PracticeSession) -> Unit = {},
    onPrepareNext: ((PracticeSession) -> Unit)? = null,
) {
    SessionHistoryEntryContent(
        entry = SessionHistoryEntry.FinishedReview(expectedSource),
        onClose = onClose,
        viewModel = viewModel,
        onOpenLocations = onOpenLocations,
        onPrepareNext = onPrepareNext,
    )
}
