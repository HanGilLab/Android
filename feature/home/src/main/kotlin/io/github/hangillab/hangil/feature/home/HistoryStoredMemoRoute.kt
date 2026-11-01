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
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState as MemoState

/** The selected history host owns reads and edits; the latest-only editor remains independent. */
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
    val observed by notes.collectAsStateWithLifecycle(MemoState.Loading(source, historyGeneration, MemoState.Token(0, 0, 0)))
    val displayed = observed
    if (displayed.source != source || displayed.historyGeneration != historyGeneration || !canKeepOpen()) return
    if (!viewModel.canRevealStoredMemo(source.sessionId, historyGeneration)) return
    if (displayed.token.readerId != 0L && !viewModel.canReadStoredMemo(displayed.token)) return
    Column(Modifier.fillMaxWidth().testTag("history_memo_content"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        when (displayed) {
            is MemoState.Loading ->
                Text(
                    stringResource(R.string.memo_loading),
                    Modifier.testTag("history_memo_loading"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is MemoState.Locked, is MemoState.Unavailable -> Unit
            is MemoState.Empty ->
                Text(
                    stringResource(R.string.history_memo_empty),
                    Modifier.testTag("history_memo_empty"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is MemoState.Failed -> {
                val message =
                    when (displayed.reason) {
                        MemoState.FailureReason.CONFLICT -> R.string.history_memo_conflict
                        MemoState.FailureReason.BLOCKED, MemoState.FailureReason.SESSION_ACTIVE -> R.string.history_memo_edit_blocked
                        MemoState.FailureReason.SESSION_CHANGED, MemoState.FailureReason.MISSING_SESSION -> R.string.memo_session_changed
                        MemoState.FailureReason.INVALID_TEXT -> R.string.memo_invalid
                        MemoState.FailureReason.WRITE_UNCONFIRMED,
                        MemoState.FailureReason.INVALID_SAVE_RESULT,
                        -> R.string.history_memo_write_unconfirmed
                        else -> R.string.memo_read_failed
                    }
                Text(
                    stringResource(message),
                    Modifier.fillMaxWidth().testTag("history_memo_failed"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                displayed.retainedDraft?.let { draft ->
                    Text(
                        stringResource(R.string.history_memo_entered_text),
                        Modifier.fillMaxWidth().testTag("history_memo_entered_label"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(draft, Modifier.fillMaxWidth().testTag("history_memo_unsaved_text"), style = MaterialTheme.typography.bodyLarge)
                }
                val retry: () -> Unit = { viewModel.retryStoredMemo(displayed.token) }
                TextButton(
                    onClick = retry,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                            .testTag("history_memo_retry")
                            .frozenObservationClick(retry),
                ) {
                    Text(
                        stringResource(if (displayed.retainedDraft == null) R.string.memo_retry else R.string.memo_reload),
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            is MemoState.Ready -> {
                Column(Modifier.testTag("history_memo_source")) { ParkedBadge(stringResource(R.string.memo_source)) }
                Text(displayed.memo.text, Modifier.testTag("history_memo_text"), style = MaterialTheme.typography.bodyLarge)
                displayed.feedback?.let { feedback ->
                    Text(
                        stringResource(if (feedback == MemoState.Feedback.SAVED) R.string.memo_saved else R.string.history_memo_reloaded),
                        Modifier.fillMaxWidth().testTag("history_memo_feedback"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (!displayed.canEdit) {
                    Text(
                        stringResource(R.string.history_memo_edit_blocked),
                        Modifier.fillMaxWidth().testTag("history_memo_edit_blocked"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                val edit: () -> Unit = { viewModel.beginStoredMemoEdit(displayed.token) }
                ParkedButton(
                    onClick = edit,
                    enabled = displayed.canEdit,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("history_memo_edit")
                            .frozenObservationClick(edit, displayed.canEdit),
                ) {
                    Text(
                        stringResource(R.string.memo_edit),
                        Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
            is MemoState.Editing -> {
                Column(Modifier.testTag("history_memo_source")) { ParkedBadge(stringResource(R.string.memo_source)) }
                HistorySessionMemoEditor(
                    displayed.source,
                    displayed.draft,
                    false,
                    { viewModel.editStoredMemo(displayed.token, displayed.nonce, it) },
                    { viewModel.saveStoredMemo(displayed.token, displayed.nonce, displayed.draft) },
                    { viewModel.cancelStoredMemoEdit(displayed.token, displayed.nonce) },
                )
            }
            is MemoState.Saving -> {
                Column(Modifier.testTag("history_memo_source")) { ParkedBadge(stringResource(R.string.memo_source)) }
                HistorySessionMemoEditor(displayed.source, displayed.draft, true, {}, {}, {})
            }
        }
    }
}
