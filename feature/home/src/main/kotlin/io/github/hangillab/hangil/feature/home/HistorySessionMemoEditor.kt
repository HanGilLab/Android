package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy

/** Input UI only; every callback belongs to the route's displayed edit receipt. */
@Composable
internal fun HistorySessionMemoEditor(
    source: PracticeSession,
    draft: String,
    saving: Boolean,
    onEdit: (String) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val intoView = remember(source) { BringIntoViewRequester() }
    var focused by remember(source) { mutableStateOf(false) }
    var height by remember(source) { mutableIntStateOf(0) }
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom, focused, height, saving) {
        if (imeBottom > 0 && focused && !saving) {
            withFrameNanos { }
            intoView.bringIntoView()
        }
    }
    LaunchedEffect(saving) {
        if (saving) {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    DisposableEffect(source) {
        onDispose {
            focus.clearFocus(force = true)
            keyboard?.hide()
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        Text(
            stringResource(R.string.history_memo_edit_context, source.courseTitle, source.taskTitle),
            Modifier.fillMaxWidth().testTag("history_memo_edit_context"),
            style = MaterialTheme.typography.bodyLarge,
        )
        OutlinedTextField(
            value = draft,
            onValueChange = onEdit,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .bringIntoViewRequester(intoView)
                    .onFocusChanged { focused = it.hasFocus }
                    .onSizeChanged { height = it.height }
                    .testTag("history_memo_textfield"),
            enabled = !saving,
            textStyle = MaterialTheme.typography.bodyLarge,
            label = { Text(stringResource(R.string.memo_field_label), style = MaterialTheme.typography.bodyLarge) },
            minLines = 3,
            maxLines = 5,
            isError = draft.isNotBlank() && !SessionMemoPolicy.isValidText(draft),
            supportingText = {
                Text(
                    stringResource(R.string.memo_count, SessionMemoPolicy.countCodePoints(draft), SessionMemoPolicy.MAX_CODE_POINTS),
                    Modifier.testTag("history_memo_count"),
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
        if (saving) {
            Text(
                stringResource(R.string.memo_writing),
                Modifier.testTag("history_memo_writing"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        val canSave = !saving && SessionMemoPolicy.isValidText(draft)
        ParkedButton(
            onClick = onSave,
            enabled = canSave,
            modifier = Modifier.fillMaxWidth().testTag("history_memo_save").frozenObservationClick(onSave, canSave),
        ) {
            Text(
                stringResource(R.string.memo_save),
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        TextButton(
            onClick = onCancel,
            enabled = !saving,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                    .testTag("history_memo_cancel_edit")
                    .frozenObservationClick(onCancel, !saving),
        ) {
            Text(
                stringResource(R.string.memo_cancel_edit),
                Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
