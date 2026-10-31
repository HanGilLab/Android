package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.domain.PracticeSession

/** Exists only inside the selected history disclosure; it never borrows a newer source. */
@Composable
internal fun HistoryStoredObservationRoute(
    source: PracticeSession,
    historyGeneration: Long,
    canKeepOpen: () -> Boolean,
    viewModel: SessionHistoryViewModel,
) {
    val observations =
        remember(source, historyGeneration, canKeepOpen, viewModel) {
            viewModel.observeStoredObservations(source, historyGeneration, canKeepOpen)
        }
    val state by observations.collectAsStateWithLifecycle(
        SessionHistoryObservationUiState.Loading(
            source,
            historyGeneration,
            SessionHistoryObservationUiState.Token(0, 0, 0),
        ),
    )
    val current = state
    if (current.source != source || current.historyGeneration != historyGeneration || !canKeepOpen()) return
    if (!viewModel.canRevealStoredObservations(source.sessionId, historyGeneration)) return
    // Initial loading contains no stored values and cannot authorize actions.
    if (current.token.readerId != 0L && !viewModel.canReadStoredObservations(current.token)) return

    Column(Modifier.fillMaxWidth().testTag("history_observation_content"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        when (current) {
            is SessionHistoryObservationUiState.Loading ->
                Text(
                    stringResource(R.string.review_loading),
                    Modifier.testTag("review_loading"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is SessionHistoryObservationUiState.Locked -> Unit
            is SessionHistoryObservationUiState.Unavailable ->
                Text(
                    stringResource(R.string.review_changed),
                    Modifier.testTag("review_unavailable"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is SessionHistoryObservationUiState.Empty ->
                Text(
                    stringResource(R.string.review_empty),
                    Modifier.testTag("review_empty"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is SessionHistoryObservationUiState.Failed -> {
                Text(
                    stringResource(R.string.review_failed),
                    Modifier.testTag("review_failed"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                val retry: () -> Unit = { viewModel.retryStoredObservations(current.token, current.selectedRowId) }
                TextButton(
                    onClick = retry,
                    modifier =
                        Modifier
                            .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                            .testTag("review_retry")
                            .frozenObservationClick(retry),
                ) { Text(stringResource(R.string.retry), style = MaterialTheme.typography.titleMedium) }
            }
            is SessionHistoryObservationUiState.Ready ->
                StoredVehicleObservationContent(
                    current.observations,
                    current.index,
                    { viewModel.previousStoredObservation(current.token, current.row.id) },
                    { viewModel.nextStoredObservation(current.token, current.row.id) },
                )
        }
    }
}
