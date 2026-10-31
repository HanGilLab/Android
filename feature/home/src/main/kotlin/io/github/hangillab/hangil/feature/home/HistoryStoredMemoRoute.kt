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
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.domain.PracticeSession

/** Read-only historical notes use their parent's source and host, not the latest memo editor. */
@Composable
internal fun HistoryStoredMemoRoute(
    source: PracticeSession,
    historyGeneration: Long,
    canKeepOpen: () -> Boolean,
    viewModel: SessionHistoryViewModel,
) {
    val notes =
        remember(source, historyGeneration, canKeepOpen, viewModel) {
            viewModel.observeStoredMemo(source, historyGeneration, canKeepOpen)
        }
    val state by notes.collectAsStateWithLifecycle(
        SessionHistoryMemoUiState.Loading(source, historyGeneration, SessionHistoryMemoUiState.Token(0, 0, 0)),
    )
    val current = state
    if (current.source != source || current.historyGeneration != historyGeneration || !canKeepOpen()) return
    if (!viewModel.canRevealStoredMemo(source.sessionId, historyGeneration)) return
    if (current.token.readerId != 0L && !viewModel.canReadStoredMemo(current.token)) return

    Column(Modifier.fillMaxWidth().testTag("history_memo_content"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        when (current) {
            is SessionHistoryMemoUiState.Loading ->
                Text(
                    stringResource(R.string.memo_loading),
                    Modifier.testTag("history_memo_loading"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is SessionHistoryMemoUiState.Locked, is SessionHistoryMemoUiState.Unavailable -> Unit
            is SessionHistoryMemoUiState.Empty ->
                Text(
                    stringResource(R.string.history_memo_empty),
                    Modifier.testTag("history_memo_empty"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is SessionHistoryMemoUiState.Failed -> {
                Text(
                    stringResource(R.string.memo_read_failed),
                    Modifier.testTag("history_memo_failed"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                val retry: () -> Unit = { viewModel.retryStoredMemo(current.token) }
                TextButton(
                    onClick = retry,
                    modifier =
                        Modifier
                            .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                            .testTag("history_memo_retry")
                            .frozenObservationClick(retry),
                ) { Text(stringResource(R.string.memo_retry), style = MaterialTheme.typography.titleMedium) }
            }
            is SessionHistoryMemoUiState.Ready -> {
                Column(Modifier.testTag("history_memo_source")) { ParkedBadge(stringResource(R.string.memo_source)) }
                Text(current.memo.text, Modifier.testTag("history_memo_text"), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}
