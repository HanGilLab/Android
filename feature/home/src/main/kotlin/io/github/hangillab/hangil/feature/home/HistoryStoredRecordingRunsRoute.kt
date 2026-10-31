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

/** Exists only in its own disclosure; it never starts SDK collection or modifies stored data. */
@Composable
internal fun HistoryStoredRecordingRunsRoute(
    source: PracticeSession,
    historyGeneration: Long,
    canKeepOpen: () -> Boolean,
    viewModel: SessionHistoryViewModel,
) {
    val runs =
        remember(source, historyGeneration, canKeepOpen, viewModel) {
            viewModel.observeStoredRecordingRuns(source, historyGeneration, canKeepOpen)
        }
    val state by runs.collectAsStateWithLifecycle(
        SessionHistoryRecordingRunUiState.Loading(source, historyGeneration, SessionHistoryRecordingRunUiState.Token(0, 0, 0)),
    )
    val current = state
    if (current.source != source || current.historyGeneration != historyGeneration || !canKeepOpen()) return
    if (!viewModel.canRevealStoredRecordingRuns(source.sessionId, historyGeneration)) return
    if (current.token.readerId != 0L && !viewModel.canReadStoredRecordingRuns(current.token)) return

    Column(Modifier.fillMaxWidth().testTag("history_recording_run_content"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        when (current) {
            is SessionHistoryRecordingRunUiState.Loading ->
                Text(
                    stringResource(R.string.history_recording_run_loading),
                    Modifier.fillMaxWidth().testTag("history_recording_run_loading"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is SessionHistoryRecordingRunUiState.Locked -> Unit
            is SessionHistoryRecordingRunUiState.Unavailable ->
                Text(
                    stringResource(R.string.history_recording_run_changed),
                    Modifier.fillMaxWidth().testTag("history_recording_run_unavailable"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is SessionHistoryRecordingRunUiState.Empty ->
                Text(
                    stringResource(R.string.history_recording_run_empty),
                    Modifier.fillMaxWidth().testTag("history_recording_run_empty"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is SessionHistoryRecordingRunUiState.Failed -> {
                Text(
                    stringResource(R.string.history_recording_run_failed),
                    Modifier.fillMaxWidth().testTag("history_recording_run_failed"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                val retry: () -> Unit = { viewModel.retryStoredRecordingRuns(current.token, current.selectedRunId) }
                TextButton(
                    onClick = retry,
                    modifier =
                        Modifier
                            .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                            .testTag("history_recording_run_retry")
                            .frozenObservationClick(retry),
                ) { Text(stringResource(R.string.history_recording_run_retry), style = MaterialTheme.typography.titleMedium) }
            }
            is SessionHistoryRecordingRunUiState.Ready ->
                StoredRecordingRunContent(
                    current,
                    { viewModel.previousStoredRecordingRun(current.token, current.run.runId) },
                    { viewModel.nextStoredRecordingRun(current.token, current.run.runId) },
                )
        }
        Text(
            stringResource(R.string.history_recording_run_scope),
            Modifier.fillMaxWidth().testTag("history_recording_run_scope"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
