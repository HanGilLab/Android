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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedDisclosure
import io.github.hangillab.hangil.core.designsystem.ParkedPanel
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState as MemoState

/** One session's user note stays independent of the selected location run or event. */
@Composable
internal fun LocationHistorySessionMemoRoute(
    source: PracticeSession,
    parentToken: LocationHistoryUiState.Token,
    viewModel: LocationHistoryViewModel,
) {
    val canKeepOpen =
        remember(source, parentToken.readerId, parentToken.queryNonce, viewModel) {
            { viewModel.canRevealSessionMemo(source, parentToken) }
        }
    if (!canKeepOpen()) return
    ParkedPanel {
        ParkedDisclosure(
            stringResource(R.string.location_history_memo),
            canKeepOpen,
            Modifier.testTag("location_history_memo"),
        ) {
            val notes =
                remember(source, parentToken.readerId, parentToken.queryNonce, canKeepOpen, viewModel) {
                    viewModel.observeSessionMemo(source, parentToken, canKeepOpen)
                }
            val observed by notes.collectAsStateWithLifecycle(
                MemoState.Loading(source, parentToken.queryNonce, MemoState.Token(0, 0, 0)),
                minActiveState = Lifecycle.State.RESUMED,
            )
            val displayed = observed
            if (displayed.source != source || displayed.historyGeneration != parentToken.queryNonce || !canKeepOpen()) {
                return@ParkedDisclosure
            }
            if (displayed.token.readerId != 0L && !viewModel.canReadSessionMemo(displayed.token)) return@ParkedDisclosure
            Column(
                Modifier.fillMaxWidth().testTag("location_history_memo_content"),
                verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
            ) {
                when (displayed) {
                    is MemoState.Loading ->
                        Text(
                            stringResource(R.string.memo_loading),
                            Modifier.fillMaxWidth().testTag("location_history_memo_loading"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    is MemoState.Empty ->
                        Text(
                            stringResource(R.string.history_memo_empty),
                            Modifier.fillMaxWidth().testTag("location_history_memo_empty"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    is MemoState.Failed -> {
                        Text(
                            stringResource(R.string.memo_read_failed),
                            Modifier.fillMaxWidth().testTag("location_history_memo_failed"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                        )
                        val retry: () -> Unit = { viewModel.retrySessionMemo(displayed.token) }
                        TextButton(
                            onClick = retry,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                                    .testTag("location_history_memo_retry")
                                    .frozenObservationClick(retry),
                        ) {
                            Text(
                                stringResource(R.string.memo_retry),
                                Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                    }
                    is MemoState.Ready -> {
                        Text(
                            stringResource(R.string.memo_source),
                            Modifier.fillMaxWidth().testTag("location_history_memo_source"),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            displayed.memo.text,
                            Modifier.fillMaxWidth().testTag("location_history_memo_text"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    is MemoState.Locked, is MemoState.Unavailable, is MemoState.Editing, is MemoState.Saving -> Unit
                }
            }
        }
    }
}
