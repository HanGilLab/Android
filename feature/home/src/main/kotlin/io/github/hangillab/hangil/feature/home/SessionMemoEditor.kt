package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy

/** Only composed inside the expanded parked memo detail. The VM retains drafts, never the IME. */
@Composable
internal fun SessionMemoEditorRoute(
    sessionId: String,
    viewModel: SessionMemoViewModel = hiltViewModel(),
) {
    val flow = remember(sessionId, viewModel) { viewModel.observe(sessionId) }
    val state by flow.collectAsStateWithLifecycle(SessionMemoUiState(sessionId))
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var deleteVersion by remember(sessionId) { mutableStateOf<String?>(null) }
    val allowed = state.sessionId == sessionId && viewModel.canRead()
    val displayedMemoVersion = state.memo?.version
    DisposableEffect(sessionId) {
        onDispose {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    LaunchedEffect(allowed, state.load, state.editing, state.memo?.version) {
        if (!allowed || state.load != SessionMemoUiState.Load.Ready || !state.editing) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
        if (!allowed || state.load != SessionMemoUiState.Load.Ready || state.memo?.version != deleteVersion) deleteVersion = null
    }
    if (!allowed) return

    Column(Modifier.fillMaxWidth().testTag("session_memo"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        when (state.load) {
            SessionMemoUiState.Load.Loading -> Text(stringResource(R.string.memo_loading), Modifier.testTag("memo_loading"))
            SessionMemoUiState.Load.Locked -> Unit
            SessionMemoUiState.Load.SessionChanged ->
                Text(
                    stringResource(R.string.memo_session_changed),
                    Modifier.testTag("memo_unavailable"),
                )
            SessionMemoUiState.Load.Failed -> {
                Text(stringResource(R.string.memo_read_failed), Modifier.testTag("memo_failed"), color = MaterialTheme.colorScheme.error)
                MemoAction(R.string.memo_retry, "memo_retry", true, false) { viewModel.retry(sessionId) }
            }
            SessionMemoUiState.Load.Ready -> {
                Column(Modifier.testTag("memo_source")) { ParkedBadge(stringResource(R.string.memo_source)) }
                if (state.editing) {
                    Text(
                        stringResource(R.string.memo_unsaved),
                        Modifier.testTag("memo_unsaved"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = state.draft,
                        onValueChange = { viewModel.edit(sessionId, it) },
                        modifier = Modifier.fillMaxWidth().testTag("memo_textfield"),
                        enabled = state.canEdit,
                        textStyle = MaterialTheme.typography.bodyLarge,
                        label = { Text(stringResource(R.string.memo_field_label), style = MaterialTheme.typography.bodyLarge) },
                        minLines = 3,
                        maxLines = 5,
                        isError = state.draft.isNotBlank() && !SessionMemoPolicy.isValidText(state.draft),
                        supportingText = {
                            Text(
                                stringResource(
                                    R.string.memo_count,
                                    SessionMemoPolicy.countCodePoints(state.draft),
                                    SessionMemoPolicy.MAX_CODE_POINTS,
                                ),
                                Modifier.testTag("memo_count"),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions =
                            KeyboardActions(onDone = {
                                focus.clearFocus(force = true)
                                keyboard?.hide()
                            }),
                    )
                    MemoAction(R.string.memo_save, "memo_save", state.canSave, true) { viewModel.save(sessionId) }
                    if (state.memo != null) {
                        MemoAction(R.string.memo_cancel_edit, "memo_cancel_edit", state.canEdit && !state.needsReload, false) {
                            viewModel.cancelEdit(sessionId)
                        }
                    }
                } else {
                    state.memo?.let {
                        Text(it.text, Modifier.testTag("memo_saved_text"), style = MaterialTheme.typography.bodyLarge)
                    }
                    MemoAction(R.string.memo_edit, "memo_edit", state.canEdit, true) { viewModel.beginEdit(sessionId) }
                    MemoAction(R.string.memo_delete, "memo_delete", state.canDelete, false) {
                        if (viewModel.canRead()) deleteVersion = displayedMemoVersion
                    }
                }
                if (state.needsReload) {
                    MemoAction(R.string.memo_reload, "memo_reload", state.operation == SessionMemoUiState.Operation.Idle, false) {
                        viewModel.reload(sessionId)
                    }
                }
            }
        }
        if (state.operation != SessionMemoUiState.Operation.Idle) {
            Text(stringResource(R.string.memo_writing), Modifier.testTag("memo_writing"), style = MaterialTheme.typography.bodyLarge)
        }
        state.feedback?.let {
            Text(
                stringResource(memoFeedback(it)),
                Modifier.testTag("memo_feedback"),
                style = MaterialTheme.typography.bodyLarge,
                color =
                    if (it == SessionMemoUiState.Feedback.Saved || it == SessionMemoUiState.Feedback.Deleted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
            )
        }
    }
    val confirmedVersion = deleteVersion
    if (
        confirmedVersion != null && state.memo?.version == confirmedVersion &&
        state.load == SessionMemoUiState.Load.Ready && state.canDelete && viewModel.canRead()
    ) {
        AlertDialog(
            onDismissRequest = { deleteVersion = null },
            title = { Text(stringResource(R.string.memo_delete_title), style = MaterialTheme.typography.titleLarge) },
            text = { Text(stringResource(R.string.memo_delete_scope), style = MaterialTheme.typography.bodyLarge) },
            confirmButton = {
                TextButton(onClick = {
                    deleteVersion = null
                    viewModel.delete(sessionId, confirmedVersion)
                }, modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("memo_confirm_delete")) {
                    Text(stringResource(R.string.memo_confirm_delete), style = MaterialTheme.typography.titleMedium)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteVersion = null },
                    modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("memo_cancel_delete"),
                ) {
                    Text(stringResource(R.string.memo_keep), style = MaterialTheme.typography.titleMedium)
                }
            },
        )
    }
}

@Composable
private fun MemoAction(
    title: Int,
    tag: String,
    enabled: Boolean,
    prominent: Boolean,
    onClick: () -> Unit,
) {
    ParkedButton(
        onClick,
        Modifier.fillMaxWidth().heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag(tag),
        enabled = enabled,
        prominent = prominent,
    ) { Text(stringResource(title), style = MaterialTheme.typography.titleMedium) }
}

private fun memoFeedback(feedback: SessionMemoUiState.Feedback): Int =
    when (feedback) {
        SessionMemoUiState.Feedback.Saved -> R.string.memo_saved
        SessionMemoUiState.Feedback.Deleted -> R.string.memo_deleted
        SessionMemoUiState.Feedback.Blocked -> R.string.memo_blocked
        SessionMemoUiState.Feedback.SessionChanged,
        SessionMemoUiState.Feedback.MissingSession,
        SessionMemoUiState.Feedback.SessionActive,
        -> R.string.memo_session_changed
        SessionMemoUiState.Feedback.Conflict -> R.string.memo_conflict
        SessionMemoUiState.Feedback.InvalidText -> R.string.memo_invalid
        SessionMemoUiState.Feedback.SaveFailed -> R.string.memo_save_failed
        SessionMemoUiState.Feedback.DeleteFailed -> R.string.memo_delete_failed
    }
